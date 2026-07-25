package com.relay.auth.util

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

@MustBeDocumented
@Constraint(validatedBy = [PasswordConstraintValidator::class])
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class PasswordConstraint(
    val message: String = "Invalid password",
    val groups: Array<KClass<out Any>> = [],
    val payload: Array<KClass<out Payload>> = []
)

class PasswordConstraintValidator : ConstraintValidator<PasswordConstraint, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext) =
        value != null
                && value.validLength()
                && value.hasDigit()
                && value.hasLowerCaseSymbol()
                && value.hasUpperCaseSymbol()
                && value.hasSpecialSymbol()
                && value.containsAllowedSymbols()

    private fun String.validLength() = this.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH

    private fun String.hasDigit() = this.any { DIGITS.contains(it) }
    private fun String.hasLowerCaseSymbol() = this.any { LOWER_CASE_SYMBOLS.contains(it) }
    private fun String.hasUpperCaseSymbol() = this.any { UPPER_CASE_SYMBOLS.contains(it) }
    private fun String.hasSpecialSymbol() = this.any { SPECIAL_SYMBOLS.contains(it) }
    private fun String.containsAllowedSymbols() = this.all { ALLOWED_SYMBOLS.contains(it) }

    companion object {
        private const val MIN_PASSWORD_LENGTH = 8
        private const val MAX_PASSWORD_LENGTH = 256
        private val DIGITS = "0123456789".toSet()
        private val LOWER_CASE_SYMBOLS = "abcdefghijklmnopqrstuvwxyz".toSet()
        private val UPPER_CASE_SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toSet()
        private val SPECIAL_SYMBOLS = "~`!@#\$%^&*()_-+={[}]|\\:;\"'<,>.?/".toSet()
        private val ALLOWED_SYMBOLS = DIGITS + LOWER_CASE_SYMBOLS + UPPER_CASE_SYMBOLS + SPECIAL_SYMBOLS
    }
}
