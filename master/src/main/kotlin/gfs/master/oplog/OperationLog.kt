package gfs.master.oplog

import gfs.proto.OperationLogEntry

interface OperationLog {

    fun append(entry: OperationLogEntry): Long

    fun readAll(): List<OperationLogEntry>

    fun readFrom(sequenceNumber: Long): List<OperationLogEntry>

    fun truncateBefore(sequenceNumber: Long)

    fun getLastSequenceNumber(): Long

    fun flush()

    fun close()
}
