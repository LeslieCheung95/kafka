/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.log

import java.nio.ByteBuffer

import kafka.api.{ApiVersion, KAFKA_2_1_IV0}
import kafka.common.LongRef
import kafka.message.{CompressionCodec, NoCompressionCodec, ZStdCompressionCodec}
import kafka.server.BrokerTopicStats
import kafka.utils.Logging
import org.apache.kafka.common.errors.{CorruptRecordException, InvalidTimestampException, UnsupportedCompressionTypeException, UnsupportedForMessageFormatException}
import org.apache.kafka.common.record.{AbstractRecords, BufferSupplier, CompressionType, MemoryRecords, Record, RecordBatch, RecordConversionStats, TimestampType}
import org.apache.kafka.common.InvalidRecordException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.utils.Time

import scala.collection.{Seq, mutable}
import scala.collection.JavaConverters._

private[kafka] object LogValidator extends Logging {

  /**
   * Update the offsets for this message set and do further validation on messages including:
   * 1. Messages for compacted topics must have keys
   * 2. When magic value >= 1, inner messages of a compressed message set must have monotonically increasing offsets
   *    starting from 0.
   * 3. When magic value >= 1, validate and maybe overwrite timestamps of messages.
   * 4. Declared count of records in DefaultRecordBatch must match number of valid records contained therein.
   *
   * This method will convert messages as necessary to the topic's configured message format version. If no format
   * conversion or value overwriting is required for messages, this method will perform in-place operations to
   * avoid expensive re-compression.
   *
   * Returns a ValidationAndOffsetAssignResult containing the validated message set, maximum timestamp, the offset
   * of the shallow message with the max timestamp and a boolean indicating whether the message sizes may have changed.
   */
  private[kafka] def validateMessagesAndAssignOffsets(records: MemoryRecords,
                                                      topicPartition: TopicPartition,
                                                      offsetCounter: LongRef,
                                                      time: Time,
                                                      now: Long,
                                                      sourceCodec: CompressionCodec,
                                                      targetCodec: CompressionCodec,
                                                      compactedTopic: Boolean,
                                                      magic: Byte,
                                                      timestampType: TimestampType,
                                                      timestampDiffMaxMs: Long,
                                                      partitionLeaderEpoch: Int,
                                                      isFromClient: Boolean,
                                                      interBrokerProtocolVersion: ApiVersion,
                                                      brokerTopicStats: BrokerTopicStats): ValidationAndOffsetAssignResult = {
    if (sourceCodec == NoCompressionCodec && targetCodec == NoCompressionCodec) {
      // check the magic value
      if (!records.hasMatchingMagic(magic))
        convertAndAssignOffsetsNonCompressed(records, topicPartition, offsetCounter, compactedTopic, time, now, timestampType,
          timestampDiffMaxMs, magic, partitionLeaderEpoch, isFromClient, brokerTopicStats)
      else
        // Do in-place validation, offset assignment and maybe set timestamp
        assignOffsetsNonCompressed(records, topicPartition, offsetCounter, now, compactedTopic, timestampType, timestampDiffMaxMs,
          partitionLeaderEpoch, isFromClient, magic, brokerTopicStats)
    } else {
      validateMessagesAndAssignOffsetsCompressed(records, topicPartition, offsetCounter, time, now, sourceCodec, targetCodec, compactedTopic,
        magic, timestampType, timestampDiffMaxMs, partitionLeaderEpoch, isFromClient, interBrokerProtocolVersion, brokerTopicStats)
    }
  }

  private[kafka] def getFirstBatchAndMaybeValidateNoMoreBatches(records: MemoryRecords, sourceCodec: CompressionCodec): RecordBatch = {
    val batchIterator = records.batches.iterator

    if (!batchIterator.hasNext) {
      throw new InvalidRecordException("Record batch has no batches at all")
    }

    val batch = batchIterator.next()

    // if the format is v2 and beyond, or if the messages are compressed, we should check there's only one batch.
    if (batch.magic() >= RecordBatch.MAGIC_VALUE_V2 || sourceCodec != NoCompressionCodec) {
      if (batchIterator.hasNext) {
        throw new InvalidRecordException("Compressed outer record has more than one batch")
      }
    }

    batch
  }

  private def validateBatch(topicPartition: TopicPartition, firstBatch: RecordBatch, batch: RecordBatch, isFromClient: Boolean, toMagic: Byte, brokerTopicStats: BrokerTopicStats): Unit = {
    // batch magic byte should have the same magic as the first batch
    if (firstBatch.magic() != batch.magic()) {
      brokerTopicStats.allTopicsStats.invalidMagicNumberRecordsPerSec.mark()
      throw new InvalidRecordException(s"Batch magic ${batch.magic()} is not the same as the first batch'es magic byte ${firstBatch.magic()} in topic partition $topicPartition.")
    }

    if (isFromClient) {
      if (batch.magic >= RecordBatch.MAGIC_VALUE_V2) {
        val countFromOffsets = batch.lastOffset - batch.baseOffset + 1
        if (countFromOffsets <= 0) {
          brokerTopicStats.allTopicsStats.invalidOffsetOrSequenceRecordsPerSec.mark()
          throw new InvalidRecordException(s"Batch has an invalid offset range: [${batch.baseOffset}, ${batch.lastOffset}] in topic partition $topicPartition.")
        }

        // v2 and above messages always have a non-null count
        val count = batch.countOrNull
        if (count <= 0) {
          brokerTopicStats.allTopicsStats.invalidOffsetOrSequenceRecordsPerSec.mark()
          throw new InvalidRecordException(s"Invalid reported count for record batch: $count in topic partition $topicPartition.")
        }

        if (countFromOffsets != batch.countOrNull) {
          brokerTopicStats.allTopicsStats.invalidOffsetOrSequenceRecordsPerSec.mark()
          throw new InvalidRecordException(s"Inconsistent batch offset range [${batch.baseOffset}, ${batch.lastOffset}] " +
            s"and count of records $count in topic partition $topicPartition.")
        }
      }

      if (batch.hasProducerId && batch.baseSequence < 0) {
        brokerTopicStats.allTopicsStats.invalidOffsetOrSequenceRecordsPerSec.mark()
        throw new InvalidRecordException(s"Invalid sequence number ${batch.baseSequence} in record batch " +
          s"with producerId ${batch.producerId} in topic partition $topicPartition.")
      }

      if (batch.isControlBatch) {
        brokerTopicStats.allTopicsStats.invalidOffsetOrSequenceRecordsPerSec.mark()
        throw new InvalidRecordException(s"Clients are not allowed to write control records in topic partition $topicPartition.")
      }
    }

    if (batch.isTransactional && toMagic < RecordBatch.MAGIC_VALUE_V2)
      throw new UnsupportedForMessageFormatException(s"Transactional records cannot be used with magic version $toMagic")

    if (batch.hasProducerId && toMagic < RecordBatch.MAGIC_VALUE_V2)
      throw new UnsupportedForMessageFormatException(s"Idempotent records cannot be used with magic version $toMagic")
  }

  private def validateRecord(batch: RecordBatch, topicPartition: TopicPartition, record: Record, now: Long, timestampType: TimestampType,
                             timestampDiffMaxMs: Long, compactedTopic: Boolean, brokerTopicStats: BrokerTopicStats): Unit = {
    if (!record.hasMagic(batch.magic)) {
      brokerTopicStats.allTopicsStats.invalidMagicNumberRecordsPerSec.mark()
      throw new InvalidRecordException(s"Log record $record's magic does not match outer magic ${batch.magic} in topic partition $topicPartition.")
    }

    // verify the record-level CRC only if this is one of the deep entries of a compressed message
    // set for magic v0 and v1. For non-compressed messages, there is no inner record for magic v0 and v1,
    // so we depend on the batch-level CRC check in Log.analyzeAndValidateRecords(). For magic v2 and above,
    // there is no record-level CRC to check.
    if (batch.magic <= RecordBatch.MAGIC_VALUE_V1 && batch.isCompressed) {
      try {
        record.ensureValid()
      } catch {
        case e: InvalidRecordException =>
          brokerTopicStats.allTopicsStats.invalidMessageCrcRecordsPerSec.mark()
          throw new CorruptRecordException(e.getMessage + s" in topic partition $topicPartition.")
      }
    }

    validateKey(record, topicPartition, compactedTopic, brokerTopicStats)
    validateTimestamp(batch, record, now, timestampType, timestampDiffMaxMs)
  }

  private def convertAndAssignOffsetsNonCompressed(records: MemoryRecords,
                                                   topicPartition: TopicPartition,
                                                   offsetCounter: LongRef,
                                                   compactedTopic: Boolean,
                                                   time: Time,
                                                   now: Long,
                                                   timestampType: TimestampType,
                                                   timestampDiffMaxMs: Long,
                                                   toMagicValue: Byte,
                                                   partitionLeaderEpoch: Int,
                                                   isFromClient: Boolean,
                                                   brokerTopicStats: BrokerTopicStats): ValidationAndOffsetAssignResult = {
    val startNanos = time.nanoseconds
    val sizeInBytesAfterConversion = AbstractRecords.estimateSizeInBytes(toMagicValue, offsetCounter.value,
      CompressionType.NONE, records.records)

    val (producerId, producerEpoch, sequence, isTransactional) = {
      val first = records.batches.asScala.head
      (first.producerId, first.producerEpoch, first.baseSequence, first.isTransactional)
    }

    val newBuffer = ByteBuffer.allocate(sizeInBytesAfterConversion)
    val builder = MemoryRecords.builder(newBuffer, toMagicValue, CompressionType.NONE, timestampType,
      offsetCounter.value, now, producerId, producerEpoch, sequence, isTransactional, partitionLeaderEpoch)

    val firstBatch = getFirstBatchAndMaybeValidateNoMoreBatches(records, NoCompressionCodec)

    for (batch <- records.batches.asScala) {
      validateBatch(topicPartition, firstBatch, batch, isFromClient, toMagicValue, brokerTopicStats)

      for (record <- batch.asScala) {
        validateRecord(batch, topicPartition, record, now, timestampType, timestampDiffMaxMs, compactedTopic, brokerTopicStats)
        builder.appendWithOffset(offsetCounter.getAndIncrement(), record)
      }
    }

    val convertedRecords = builder.build()

    val info = builder.info
    val recordConversionStats = new RecordConversionStats(builder.uncompressedBytesWritten,
      builder.numRecords, time.nanoseconds - startNanos)
    ValidationAndOffsetAssignResult(
      validatedRecords = convertedRecords,
      maxTimestamp = info.maxTimestamp,
      shallowOffsetOfMaxTimestamp = info.shallowOffsetOfMaxTimestamp,
      messageSizeMaybeChanged = true,
      recordConversionStats = recordConversionStats)
  }

  private def assignOffsetsNonCompressed(records: MemoryRecords,
                                         topicPartition: TopicPartition,
                                         offsetCounter: LongRef,
                                         now: Long,
                                         compactedTopic: Boolean,
                                         timestampType: TimestampType,
                                         timestampDiffMaxMs: Long,
                                         partitionLeaderEpoch: Int,
                                         isFromClient: Boolean,
                                         magic: Byte,
                                         brokerTopicStats: BrokerTopicStats): ValidationAndOffsetAssignResult = {
    var maxTimestamp = RecordBatch.NO_TIMESTAMP
    var offsetOfMaxTimestamp = -1L
    val initialOffset = offsetCounter.value

    val firstBatch = getFirstBatchAndMaybeValidateNoMoreBatches(records, NoCompressionCodec)

    for (batch <- records.batches.asScala) {
      validateBatch(topicPartition, firstBatch, batch, isFromClient, magic, brokerTopicStats)

      var maxBatchTimestamp = RecordBatch.NO_TIMESTAMP
      var offsetOfMaxBatchTimestamp = -1L

      for (record <- batch.asScala) {
        validateRecord(batch, topicPartition, record, now, timestampType, timestampDiffMaxMs, compactedTopic, brokerTopicStats)
        val offset = offsetCounter.getAndIncrement()
        if (batch.magic > RecordBatch.MAGIC_VALUE_V0 && record.timestamp > maxBatchTimestamp) {
          maxBatchTimestamp = record.timestamp
          offsetOfMaxBatchTimestamp = offset
        }
      }

      if (batch.magic > RecordBatch.MAGIC_VALUE_V0 && maxBatchTimestamp > maxTimestamp) {
        maxTimestamp = maxBatchTimestamp
        offsetOfMaxTimestamp = offsetOfMaxBatchTimestamp
      }

      batch.setLastOffset(offsetCounter.value - 1)

      if (batch.magic >= RecordBatch.MAGIC_VALUE_V2)
        batch.setPartitionLeaderEpoch(partitionLeaderEpoch)

      if (batch.magic > RecordBatch.MAGIC_VALUE_V0) {
        if (timestampType == TimestampType.LOG_APPEND_TIME)
          batch.setMaxTimestamp(TimestampType.LOG_APPEND_TIME, now)
        else
          batch.setMaxTimestamp(timestampType, maxBatchTimestamp)
      }
    }

    if (timestampType == TimestampType.LOG_APPEND_TIME) {
      maxTimestamp = now
      if (magic >= RecordBatch.MAGIC_VALUE_V2)
        offsetOfMaxTimestamp = offsetCounter.value - 1
      else
        offsetOfMaxTimestamp = initialOffset
    }

    ValidationAndOffsetAssignResult(
      validatedRecords = records,
      maxTimestamp = maxTimestamp,
      shallowOffsetOfMaxTimestamp = offsetOfMaxTimestamp,
      messageSizeMaybeChanged = false,
      recordConversionStats = RecordConversionStats.EMPTY)
  }

  /**
   * We cannot do in place assignment in one of the following situations:
   * 1. Source and target compression codec are different
   * 2. When the target magic is not equal to batches' magic, meaning format conversion is needed.
   * 3. When the target magic is equal to V0, meaning absolute offsets need to be re-assigned.
   */
  def validateMessagesAndAssignOffsetsCompressed(records: MemoryRecords,
                                                 topicPartition: TopicPartition,
                                                 offsetCounter: LongRef,
                                                 time: Time,
                                                 now: Long,
                                                 sourceCodec: CompressionCodec,
                                                 targetCodec: CompressionCodec,
                                                 compactedTopic: Boolean,
                                                 toMagic: Byte,
                                                 timestampType: TimestampType,
                                                 timestampDiffMaxMs: Long,
                                                 partitionLeaderEpoch: Int,
                                                 isFromClient: Boolean,
                                                 interBrokerProtocolVersion: ApiVersion,
                                                 brokerTopicStats: BrokerTopicStats): ValidationAndOffsetAssignResult = {

    if (targetCodec == ZStdCompressionCodec && interBrokerProtocolVersion < KAFKA_2_1_IV0)
      throw new UnsupportedCompressionTypeException("Produce requests to inter.broker.protocol.version < 2.1 broker " +
        "are not allowed to use ZStandard compression")

    // No in place assignment situation 1
    var inPlaceAssignment = sourceCodec == targetCodec

    var maxTimestamp = RecordBatch.NO_TIMESTAMP
    val expectedInnerOffset = new LongRef(0)
    val validatedRecords = new mutable.ArrayBuffer[Record]

    var uncompressedSizeInBytes = 0

    // Assume there's only one batch with compressed memory records; otherwise, return InvalidRecordException
    // One exception though is that with format smaller than v2, if sourceCodec is noCompression, then each batch is actually
    // a single record so we'd need to special handle it by creating a single wrapper batch that includes all the records
    val firstBatch = getFirstBatchAndMaybeValidateNoMoreBatches(records, sourceCodec)

    // No in place assignment situation 2 and 3: we only need to check for the first batch because:
    //  1. For most cases (compressed records, v2, for example), there's only one batch anyways.
    //  2. For cases that there may be multiple batches, all batches' magic should be the same.
    if (firstBatch.magic != toMagic || toMagic == RecordBatch.MAGIC_VALUE_V0)
      inPlaceAssignment = false

    // Do not compress control records unless they are written compressed
    if (sourceCodec == NoCompressionCodec && firstBatch.isControlBatch)
      inPlaceAssignment = true

    val batches = records.batches.asScala
    for (batch <- batches) {
      validateBatch(topicPartition, firstBatch, batch, isFromClient, toMagic, brokerTopicStats)
      uncompressedSizeInBytes += AbstractRecords.recordBatchHeaderSizeInBytes(toMagic, batch.compressionType())

      // if we are on version 2 and beyond, and we know we are going for in place assignment,
      // then we can optimize the iterator to skip key / value / headers since they would not be used at all
      val recordsIterator = if (inPlaceAssignment && firstBatch.magic >= RecordBatch.MAGIC_VALUE_V2)
        batch.skipKeyValueIterator(BufferSupplier.NO_CACHING)
      else
        batch.streamingIterator(BufferSupplier.NO_CACHING)

      try {
        for (record <- batch.asScala) {
          if (sourceCodec != NoCompressionCodec && record.isCompressed)
            throw new InvalidRecordException("Compressed outer record should not have an inner record with a " +
              s"compression attribute set: $record")
          validateRecord(batch, topicPartition, record, now, timestampType, timestampDiffMaxMs, compactedTopic, brokerTopicStats)

          uncompressedSizeInBytes += record.sizeInBytes()
          if (batch.magic > RecordBatch.MAGIC_VALUE_V0 && toMagic > RecordBatch.MAGIC_VALUE_V0) {
            // inner records offset should always be continuous
            val expectedOffset = expectedInnerOffset.getAndIncrement()
            if (record.offset != expectedOffset) {
              brokerTopicStats.allTopicsStats.invalidOffsetOrSequenceRecordsPerSec.mark()
              throw new InvalidRecordException(s"Inner record $record inside the compressed record batch does not have incremental offsets, expected offset is $expectedOffset in topic partition $topicPartition.")
            }
            if (record.timestamp > maxTimestamp)
              maxTimestamp = record.timestamp
          }

          validatedRecords += record
        }
      } finally {
        recordsIterator.close()
      }
    }

    if (!inPlaceAssignment) {
      val (producerId, producerEpoch, sequence, isTransactional) = {
        // note that we only reassign offsets for requests coming straight from a producer. For records with magic V2,
        // there should be exactly one RecordBatch per request, so the following is all we need to do. For Records
        // with older magic versions, there will never be a producer id, etc.
        val first = records.batches.asScala.head
        (first.producerId, first.producerEpoch, first.baseSequence, first.isTransactional)
      }
      buildRecordsAndAssignOffsets(toMagic, offsetCounter, time, timestampType, CompressionType.forId(targetCodec.codec), now,
        validatedRecords, producerId, producerEpoch, sequence, isTransactional, partitionLeaderEpoch, isFromClient,
        uncompressedSizeInBytes)
    } else {
      // we can update the batch only and write the compressed payload as is;
      // again we assume only one record batch within the compressed set
      val batch = records.batches.iterator.next()
      val lastOffset = offsetCounter.addAndGet(validatedRecords.size) - 1

      batch.setLastOffset(lastOffset)

      if (timestampType == TimestampType.LOG_APPEND_TIME)
        maxTimestamp = now

      if (toMagic >= RecordBatch.MAGIC_VALUE_V1)
        batch.setMaxTimestamp(timestampType, maxTimestamp)

      if (toMagic >= RecordBatch.MAGIC_VALUE_V2)
        batch.setPartitionLeaderEpoch(partitionLeaderEpoch)

      val recordConversionStats = new RecordConversionStats(uncompressedSizeInBytes, 0, 0)
      ValidationAndOffsetAssignResult(validatedRecords = records,
        maxTimestamp = maxTimestamp,
        shallowOffsetOfMaxTimestamp = lastOffset,
        messageSizeMaybeChanged = false,
        recordConversionStats = recordConversionStats)
    }
  }

  private def buildRecordsAndAssignOffsets(magic: Byte,
                                           offsetCounter: LongRef,
                                           time: Time,
                                           timestampType: TimestampType,
                                           compressionType: CompressionType,
                                           logAppendTime: Long,
                                           validatedRecords: Seq[Record],
                                           producerId: Long,
                                           producerEpoch: Short,
                                           baseSequence: Int,
                                           isTransactional: Boolean,
                                           partitionLeaderEpoch: Int,
                                           isFromClient: Boolean,
                                           uncompressedSizeInBytes: Int): ValidationAndOffsetAssignResult = {
    val startNanos = time.nanoseconds
    val estimatedSize = AbstractRecords.estimateSizeInBytes(magic, offsetCounter.value, compressionType,
      validatedRecords.asJava)
    val buffer = ByteBuffer.allocate(estimatedSize)
    val builder = MemoryRecords.builder(buffer, magic, compressionType, timestampType, offsetCounter.value,
      logAppendTime, producerId, producerEpoch, baseSequence, isTransactional, partitionLeaderEpoch)

    validatedRecords.foreach { record =>
      builder.appendWithOffset(offsetCounter.getAndIncrement(), record)
    }

    val records = builder.build()

    val info = builder.info

    // This is not strictly correct, it represents the number of records where in-place assignment is not possible
    // instead of the number of records that were converted. It will over-count cases where the source and target are
    // message format V0 or if the inner offsets are not consecutive. This is OK since the impact is the same: we have
    // to rebuild the records (including recompression if enabled).
    val conversionCount = builder.numRecords
    val recordConversionStats = new RecordConversionStats(uncompressedSizeInBytes + builder.uncompressedBytesWritten,
      conversionCount, time.nanoseconds - startNanos)

    ValidationAndOffsetAssignResult(
      validatedRecords = records,
      maxTimestamp = info.maxTimestamp,
      shallowOffsetOfMaxTimestamp = info.shallowOffsetOfMaxTimestamp,
      messageSizeMaybeChanged = true,
      recordConversionStats = recordConversionStats)
  }

  private def validateKey(record: Record, topicPartition: TopicPartition, compactedTopic: Boolean, brokerTopicStats: BrokerTopicStats) {
    if (compactedTopic && !record.hasKey) {
      brokerTopicStats.allTopicsStats.noKeyCompactedTopicRecordsPerSec.mark()
      throw new InvalidRecordException(s"Compacted topic cannot accept message without key in topic partition $topicPartition.")
    }
  }

  /**
   * This method validates the timestamps of a message.
   * If the message is using create time, this method checks if it is within acceptable range.
   */
  private def validateTimestamp(batch: RecordBatch,
                                record: Record,
                                now: Long,
                                timestampType: TimestampType,
                                timestampDiffMaxMs: Long): Unit = {
    if (timestampType == TimestampType.CREATE_TIME
      && record.timestamp != RecordBatch.NO_TIMESTAMP
      && math.abs(record.timestamp - now) > timestampDiffMaxMs)
      throw new InvalidTimestampException(s"Timestamp ${record.timestamp} of message with offset ${record.offset} is " +
        s"out of range. The timestamp should be within [${now - timestampDiffMaxMs}, ${now + timestampDiffMaxMs}]")
    if (batch.timestampType == TimestampType.LOG_APPEND_TIME)
      throw new InvalidTimestampException(s"Invalid timestamp type in message $record. Producer should not set " +
        s"timestamp type to LogAppendTime.")
  }

  case class ValidationAndOffsetAssignResult(validatedRecords: MemoryRecords,
                                             maxTimestamp: Long,
                                             shallowOffsetOfMaxTimestamp: Long,
                                             messageSizeMaybeChanged: Boolean,
                                             recordConversionStats: RecordConversionStats)

}
