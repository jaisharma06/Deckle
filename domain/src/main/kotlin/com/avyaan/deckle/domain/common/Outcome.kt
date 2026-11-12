package com.avyaan.deckle.domain.common

/**
 * Result of an operation that can fail in an *expected* way.
 *
 * Why not `kotlin.Result`: it carries an untyped Throwable, so the UI would have to sniff exception
 * types to pick a message. [DeckleError] maps 1:1 to user-facing text (spec 01 §8).
 */
public sealed interface Outcome<out T> {
    public data class Success<T>(val value: T) : Outcome<T>
    public data class Failure(val error: DeckleError) : Outcome<Nothing>
}

public inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

public inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

public inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> = also { if (it is Outcome.Success) action(it.value) }

public inline fun <T> Outcome<T>.onFailure(action: (DeckleError) -> Unit): Outcome<T> = also { if (it is Outcome.Failure) action(it.error) }

public fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value

public fun <T> Outcome<T>.errorOrNull(): DeckleError? = (this as? Outcome.Failure)?.error
