package com.maghizhan.tabby.data.local

import androidx.room.withTransaction

/**
 * Runs a block inside a database transaction.
 *
 * An interface rather than a direct `RoomDatabase.withTransaction` call so the
 * sync coordinator can be unit-tested without Room, *and* so a test can assert
 * the atomicity guarantee itself: a fake can abort mid-block and verify nothing
 * was applied.
 */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/** The real implementation, backed by Room. */
class RoomTransactionRunner(private val database: TabbyDatabase) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }
}
