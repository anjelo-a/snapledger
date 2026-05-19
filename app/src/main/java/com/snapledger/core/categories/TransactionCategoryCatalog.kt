package com.snapledger.core.categories

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.snapledger.R

data class TransactionCategoryOption(
    val name: String,
    @DrawableRes val iconResId: Int,
    val tintColor: Color,
)

private val expenseCategoryOptions = listOf(
    TransactionCategoryOption("Food", R.drawable.utensils, Color(0xFF4CAF50)),
    TransactionCategoryOption("Transport", R.drawable.car, Color(0xFF009688)),
    TransactionCategoryOption("Shopping", R.drawable.shopping_basket, Color(0xFFE91E63)),
    TransactionCategoryOption("Bills", R.drawable.receipt, Color(0xFFFFC107)),
    TransactionCategoryOption("Entertainment", R.drawable.film, Color(0xFF9C27B0)),
    TransactionCategoryOption("Health", R.drawable.heart_pulse, Color(0xFFFF9800)),
    TransactionCategoryOption("Personal", R.drawable.heart_pulse, Color(0xFFFF6F91)),
    TransactionCategoryOption("Household", R.drawable.home, Color(0xFF3F51B5)),
    TransactionCategoryOption("Education", R.drawable.list, Color(0xFF5C6BC0)),
    TransactionCategoryOption("Travel", R.drawable.car, Color(0xFF00ACC1)),
    TransactionCategoryOption("Misc", R.drawable.astroid, Color(0xFF8D6E63)),
    TransactionCategoryOption("Other", R.drawable.box, Color(0xFF9E9E9E)),
)

private val incomeCategoryOptions = listOf(
    TransactionCategoryOption("Salary", R.drawable.hand_coins, Color(0xFF00A86B)),
    TransactionCategoryOption("Freelance", R.drawable.banknote_arrow_up, Color(0xFF009688)),
    TransactionCategoryOption("Investments", R.drawable.trending_up, Color(0xFF4CAF50)),
    TransactionCategoryOption("Stipend", R.drawable.receipt, Color(0xFF7F22FE)),
    TransactionCategoryOption("Allowance", R.drawable.hand_coins, Color(0xFFFF9800)),
    TransactionCategoryOption("Bonus", R.drawable.trending_up, Color(0xFFE91E63)),
    TransactionCategoryOption("Business", R.drawable.box, Color(0xFF3F51B5)),
    TransactionCategoryOption("Gifts", R.drawable.heart_pulse, Color(0xFFFF5252)),
    TransactionCategoryOption("Other", R.drawable.box, Color(0xFF9E9E9E)),
)

fun expenseTransactionCategories(): List<TransactionCategoryOption> = expenseCategoryOptions

fun incomeTransactionCategories(): List<TransactionCategoryOption> = incomeCategoryOptions

fun expenseTransactionCategoryNames(): List<String> = expenseCategoryOptions.map { it.name }

fun historyTransactionCategoryNames(): List<String> {
    return buildList {
        add("All")
        addAll(expenseTransactionCategoryNames())
        add("Income")
    }
}

fun transactionCategoryOptionForName(categoryName: String): TransactionCategoryOption {
    val normalized = categoryName.trim()
    return (expenseCategoryOptions + incomeCategoryOptions).firstOrNull {
        it.name.equals(normalized, ignoreCase = true)
    } ?: TransactionCategoryOption(
        name = normalized.ifBlank { "Other" },
        iconResId = R.drawable.box,
        tintColor = Color(0xFF9E9E9E),
    )
}
