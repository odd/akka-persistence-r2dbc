/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.r2dbc.internal

import scala.collection.immutable
import java.time.Instant
import java.time.{ Duration => JDuration }

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import akka.NotUsed
import akka.annotation.InternalApi
import akka.persistence.query.Offset
import akka.persistence.query.TimestampOffset
import akka.persistence.r2dbc.R2dbcSettings
import akka.persistence.r2dbc.internal.BySliceQuery.Buckets.Bucket
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import org.slf4j.Logger

/**
 * INTERNAL API
 */
@InternalApi private[r2dbc] object BySliceQuery {
  val EmptyDbTimestamp: Instant = Instant.EPOCH

  object QueryState {
    val empty: QueryState =
      QueryState(TimestampOffset.Zero, 0, 0, 0, backtracking = false, TimestampOffset.Zero, Buckets.empty)
  }

  final case class QueryState(
      latest: TimestampOffset,
      rowCount: Int,
      queryCount: Long,
      idleCount: Long,
      backtracking: Boolean,
      latestBacktracking: TimestampOffset,
      buckets: Buckets) {

    def currentOffset: TimestampOffset =
      if (backtracking) latestBacktracking
      else latest

    def nextQueryFromTimestamp: Instant =
      if (backtracking) latestBacktracking.timestamp
      else latest.timestamp

    def nextQueryToTimestamp(atLeastNumberOfEvents: Int): Option[Instant] = {
      buckets.findTimeForLimit(nextQueryFromTimestamp, atLeastNumberOfEvents) match {
        case Some(t) =>
          if (backtracking)
            if (t.isAfter(latest.timestamp)) Some(latest.timestamp) else Some(t)
          else
            Some(t)
        case None =>
          if (backtracking) Some(latest.timestamp)
          else None
      }
    }
  }

  object Buckets {
    type EpochSeconds = Long
    type Count = Long

    val empty = new Buckets(immutable.SortedMap.empty)
    // Note that 10 seconds is also defined in the aggregation sql in the dao, so be cautious if you change this.
    val BucketDurationSeconds = 10
    val Limit = 10000

    final case class Bucket(startTime: EpochSeconds, count: Count)
  }

  /**
   * Count of events or state changes per 10 seconds time bucket is retrieved from database (infrequently) with an
   * aggregation query. This is used for estimating an upper bound of `db_timestamp < ?` in the `eventsBySlices` and
   * `changesBySlices` database queries. It is important to reduce the result set in this way because the `LIMIT` is
   * used after sorting the rows. See issue #/178 for more background info..
   *
   * @param countByBucket
   *   Key is the epoch seconds for the start of the bucket. Value is the number of entries in the bucket.
   */
  class Buckets(countByBucket: immutable.SortedMap[Buckets.EpochSeconds, Buckets.Count]) {
    import Buckets.{ Bucket, BucketDurationSeconds, Count, EpochSeconds }

    val createdAt: Instant = Instant.now()

    def findTimeForLimit(from: Instant, atLeastCounts: Int): Option[Instant] = {
      val fromEpochSeconds = from.toEpochMilli / 1000
      val iter = countByBucket.iterator.dropWhile { case (key, _) => fromEpochSeconds >= key }

      @tailrec def sumUntilFilled(key: EpochSeconds, sum: Count): (EpochSeconds, Count) = {
        if (iter.isEmpty || sum >= atLeastCounts)
          key -> sum
        else {
          val (nextKey, count) = iter.next()
          sumUntilFilled(nextKey, sum + count)
        }
      }

      val (key, sum) = sumUntilFilled(fromEpochSeconds, 0)
      if (sum >= atLeastCounts)
        Some(Instant.ofEpochSecond(key + BucketDurationSeconds))
      else
        None
    }

    // Key is the epoch seconds for the start of the bucket.
    // Value is the number of entries in the bucket.
    def add(bucketCounts: Seq[Bucket]): Buckets =
      new Buckets(countByBucket ++ bucketCounts.iterator.map { case Bucket(startTime, count) => startTime -> count })

    def clearUntil(time: Instant): Buckets = {
      val epochSeconds = time.minusSeconds(BucketDurationSeconds).toEpochMilli / 1000
      val newCountByBucket = countByBucket.dropWhile { case (key, _) => epochSeconds >= key }
      if (newCountByBucket.size == countByBucket.size)
        this
      else if (newCountByBucket.isEmpty)
        new Buckets(immutable.SortedMap(countByBucket.last)) // keep last
      else
        new Buckets(newCountByBucket)
    }

    def isEmpty: Boolean = countByBucket.isEmpty

    def size: Int = countByBucket.size

    override def toString: String = {
      s"Buckets(${countByBucket.mkString(", ")})"
    }
  }

  trait SerializedRow {
    def persistenceId: String
    def seqNr: Long
    def dbTimestamp: Instant
    def readDbTimestamp: Instant
  }

  trait Dao[SerializedRow] {
    def currentDbTimestamp(): Future[Instant]

    def rowsBySlices(
        entityType: String,
        minSlice: Int,
        maxSlice: Int,
        fromTimestamp: Instant,
        toTimestamp: Option[Instant],
        behindCurrentTime: FiniteDuration,
        backtracking: Boolean): Source[SerializedRow, NotUsed]

    /**
     * For Durable State we always refresh the bucket counts at the interval. For Event Sourced we know that they don't
     * change because events are append only.
     */
    def countBucketsMayChange: Boolean

    def countBuckets(
        entityType: String,
        minSlice: Int,
        maxSlice: Int,
        fromTimestamp: Instant,
        limit: Int): Future[Seq[Bucket]]

  }
}

/**
 * INTERNAL API
 */
@InternalApi private[r2dbc] class BySliceQuery[Row <: BySliceQuery.SerializedRow, Envelope](
    dao: BySliceQuery.Dao[Row],
    createEnvelope: (TimestampOffset, Row) => Envelope,
    extractOffset: Envelope => TimestampOffset,
    settings: R2dbcSettings,
    log: Logger)(implicit val ec: ExecutionContext) {
  import BySliceQuery._
  import TimestampOffset.toTimestampOffset

  private val backtrackingWindow = JDuration.ofMillis(settings.querySettings.backtrackingWindow.toMillis)
  private val halfBacktrackingWindow = backtrackingWindow.dividedBy(2)
  private val firstBacktrackingQueryWindow =
    backtrackingWindow.plus(JDuration.ofMillis(settings.querySettings.backtrackingBehindCurrentTime.toMillis))
  private val eventBucketCountInterval = JDuration.ofSeconds(60)

  def currentBySlices(
      logPrefix: String,
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[Envelope, NotUsed] = {
    val initialOffset = toTimestampOffset(offset)

    def nextOffset(state: QueryState, envelope: Envelope): QueryState =
      state.copy(latest = extractOffset(envelope), rowCount = state.rowCount + 1)

    def nextQuery(state: QueryState, endTimestamp: Instant): (QueryState, Option[Source[Envelope, NotUsed]]) = {
      // Note that we can't know how many events with the same timestamp that are filtered out
      // so continue until rowCount is 0. That means an extra query at the end to make sure there are no
      // more to fetch.
      if (state.queryCount == 0L || state.rowCount > 0) {
        val newState = state.copy(rowCount = 0, queryCount = state.queryCount + 1)

        val toTimestamp = newState.nextQueryToTimestamp(settings.querySettings.bufferSize) match {
          case Some(t) =>
            if (t.isBefore(endTimestamp)) t else endTimestamp
          case None =>
            endTimestamp
        }

        if (state.queryCount != 0 && log.isDebugEnabled())
          log.debug(
            "{} next query [{}] from slices [{} - {}], between time [{} - {}]. Found [{}] rows in previous query.",
            logPrefix,
            state.queryCount,
            minSlice,
            maxSlice,
            state.latest.timestamp,
            toTimestamp,
            state.rowCount)

        newState -> Some(
          dao
            .rowsBySlices(
              entityType,
              minSlice,
              maxSlice,
              state.latest.timestamp,
              toTimestamp = Some(toTimestamp),
              behindCurrentTime = Duration.Zero,
              backtracking = false)
            .via(deserializeAndAddOffset(state.latest)))
      } else {
        if (log.isDebugEnabled)
          log.debug(
            "{} query [{}] from slices [{} - {}] completed. Found [{}] rows in previous query.",
            logPrefix,
            state.queryCount,
            minSlice,
            maxSlice,
            state.rowCount)

        state -> None
      }
    }

    Source
      .futureSource[Envelope, NotUsed] {
        dao.currentDbTimestamp().map { currentDbTime =>
          if (log.isDebugEnabled())
            log.debug(
              "{} query slices [{} - {}], from time [{}] until now [{}].",
              logPrefix,
              minSlice,
              maxSlice,
              initialOffset.timestamp,
              currentDbTime)

          ContinuousQuery[QueryState, Envelope](
            initialState = QueryState.empty.copy(latest = initialOffset),
            updateState = nextOffset,
            delayNextQuery = _ => None,
            nextQuery = state => nextQuery(state, currentDbTime),
            beforeQuery = beforeQuery(logPrefix, entityType, minSlice, maxSlice, _))
        }
      }
      .mapMaterializedValue(_ => NotUsed)
  }

  def liveBySlices(
      logPrefix: String,
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[Envelope, NotUsed] = {
    val initialOffset = toTimestampOffset(offset)

    if (log.isDebugEnabled())
      log.debug(
        "Starting {} query from slices [{} - {}], from time [{}].",
        logPrefix,
        minSlice,
        maxSlice,
        initialOffset.timestamp)

    def nextOffset(state: QueryState, envelope: Envelope): QueryState = {
      val offset = extractOffset(envelope)
      if (state.backtracking) {
        if (offset.timestamp.isBefore(state.latestBacktracking.timestamp))
          throw new IllegalArgumentException(
            s"Unexpected offset [$offset] before latestBacktracking [${state.latestBacktracking}].")

        state.copy(latestBacktracking = offset, rowCount = state.rowCount + 1)
      } else {
        if (offset.timestamp.isBefore(state.latest.timestamp))
          throw new IllegalArgumentException(s"Unexpected offset [$offset] before latest [${state.latest}].")

        state.copy(latest = offset, rowCount = state.rowCount + 1)
      }
    }

    def delayNextQuery(state: QueryState): Option[FiniteDuration] = {
      val delay = ContinuousQuery.adjustNextDelay(
        state.rowCount,
        settings.querySettings.bufferSize,
        settings.querySettings.refreshInterval)

      if (log.isDebugEnabled)
        delay.foreach { d =>
          log.debug(
            "{} query [{}] from slices [{} - {}] delay next [{}] ms.",
            logPrefix,
            state.queryCount,
            minSlice,
            maxSlice,
            d.toMillis)
        }

      delay
    }

    def nextQuery(state: QueryState): (QueryState, Option[Source[Envelope, NotUsed]]) = {
      val newIdleCount = if (state.rowCount == 0) state.idleCount + 1 else 0
      val newState =
        if (settings.querySettings.backtrackingEnabled && !state.backtracking && state.latest != TimestampOffset.Zero &&
          (newIdleCount >= 5 || JDuration
            .between(state.latestBacktracking.timestamp, state.latest.timestamp)
            .compareTo(halfBacktrackingWindow) > 0)) {
          // FIXME config for newIdleCount >= 5 and maybe something like `newIdleCount % 5 == 0`

          // switching to backtracking
          val fromOffset =
            if (state.latestBacktracking == TimestampOffset.Zero)
              TimestampOffset.Zero.copy(timestamp = state.latest.timestamp.minus(firstBacktrackingQueryWindow))
            else
              state.latestBacktracking

          state.copy(
            rowCount = 0,
            queryCount = state.queryCount + 1,
            idleCount = newIdleCount,
            backtracking = true,
            latestBacktracking = fromOffset)
        } else if (state.backtracking && state.rowCount < settings.querySettings.bufferSize - 1) {
          // switch from backtracking
          state.copy(rowCount = 0, queryCount = state.queryCount + 1, idleCount = newIdleCount, backtracking = false)
        } else {
          state.copy(rowCount = 0, queryCount = state.queryCount + 1, idleCount = newIdleCount)
        }

      val behindCurrentTime =
        if (newState.backtracking) settings.querySettings.backtrackingBehindCurrentTime
        else settings.querySettings.behindCurrentTime

      val fromTimestamp = newState.nextQueryFromTimestamp
      val toTimestamp = newState.nextQueryToTimestamp(settings.querySettings.bufferSize)

      if (log.isDebugEnabled())
        log.debug(
          "{} next query [{}]{} from slices [{} - {}], between time [{} - {}]. {}",
          logPrefix,
          newState.queryCount,
          if (newState.backtracking) " in backtracking mode" else "",
          minSlice,
          maxSlice,
          fromTimestamp,
          toTimestamp.getOrElse("None"),
          if (newIdleCount >= 3) s"Idle in [$newIdleCount] queries."
          else if (state.backtracking) s"Found [${state.rowCount}] rows in previous backtracking query."
          else s"Found [${state.rowCount}] rows in previous query.")

      newState ->
      Some(
        dao
          .rowsBySlices(
            entityType,
            minSlice,
            maxSlice,
            fromTimestamp,
            toTimestamp,
            behindCurrentTime,
            backtracking = newState.backtracking)
          .via(deserializeAndAddOffset(newState.currentOffset)))
    }

    ContinuousQuery[QueryState, Envelope](
      initialState = QueryState.empty.copy(latest = initialOffset),
      updateState = nextOffset,
      delayNextQuery = delayNextQuery,
      nextQuery = nextQuery,
      beforeQuery = beforeQuery(logPrefix, entityType, minSlice, maxSlice, _))
  }

  private def beforeQuery(
      logPrefix: String,
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      state: QueryState): Option[Future[QueryState]] = {
    // Don't run this too frequently
    if ((state.buckets.isEmpty || JDuration
        .between(state.buckets.createdAt, Instant.now())
        .compareTo(eventBucketCountInterval) > 0) &&
      // For Durable State we always refresh the bucket counts at the interval. For Event Sourced we know
      // that they don't change because events are append only.
      (dao.countBucketsMayChange || state.buckets
        .findTimeForLimit(state.latest.timestamp, settings.querySettings.bufferSize)
        .isEmpty)) {

      val fromTimestamp =
        if (state.latestBacktracking.timestamp == Instant.EPOCH && state.latest.timestamp == Instant.EPOCH)
          Instant.EPOCH
        else if (state.latestBacktracking.timestamp == Instant.EPOCH)
          state.latest.timestamp.minus(firstBacktrackingQueryWindow)
        else
          state.latestBacktracking.timestamp

      val futureState =
        dao.countBuckets(entityType, minSlice, maxSlice, fromTimestamp, Buckets.Limit).map { counts =>
          val newBuckets = state.buckets.clearUntil(fromTimestamp).add(counts)
          val newState = state.copy(buckets = newBuckets)
          if (log.isDebugEnabled) {
            val sum = counts.iterator.map { case Bucket(_, count) => count }.sum
            log.debug(
              "{} retrieved [{}] event count buckets, with a total of [{}], from slices [{} - {}], from time [{}]",
              logPrefix,
              counts.size,
              sum,
              minSlice,
              maxSlice,
              fromTimestamp)
          }
          newState
        }
      Some(futureState)
    } else {
      // already enough buckets or retrieved recently
      None
    }
  }

  // TODO Unit test in isolation
  private def deserializeAndAddOffset(timestampOffset: TimestampOffset): Flow[Row, Envelope, NotUsed] = {
    Flow[Row].statefulMapConcat { () =>
      var currentTimestamp = timestampOffset.timestamp
      var currentSequenceNrs: Map[String, Long] = timestampOffset.seen
      row => {
        if (row.dbTimestamp == currentTimestamp) {
          // has this already been seen?
          if (currentSequenceNrs.get(row.persistenceId).exists(_ >= row.seqNr)) {
            if (currentSequenceNrs.size >= settings.querySettings.bufferSize) {
              throw new IllegalStateException(
                s"Too many events stored with the same timestamp [$currentTimestamp], buffer size [${settings.querySettings.bufferSize}]")
            }
            log.trace(
              "filtering [{}] [{}] as db timestamp is the same as last offset and is in seen [{}]",
              row.persistenceId,
              row.seqNr,
              currentSequenceNrs)
            Nil
          } else {
            currentSequenceNrs = currentSequenceNrs.updated(row.persistenceId, row.seqNr)
            val offset =
              TimestampOffset(row.dbTimestamp, row.readDbTimestamp, currentSequenceNrs)
            createEnvelope(offset, row) :: Nil
          }
        } else {
          // ne timestamp, reset currentSequenceNrs
          currentTimestamp = row.dbTimestamp
          currentSequenceNrs = Map(row.persistenceId -> row.seqNr)
          val offset = TimestampOffset(row.dbTimestamp, row.readDbTimestamp, currentSequenceNrs)
          createEnvelope(offset, row) :: Nil
        }
      }
    }
  }
}
