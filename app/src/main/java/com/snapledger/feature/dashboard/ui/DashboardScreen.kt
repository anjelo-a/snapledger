package com.snapledger.feature.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapledger.R
import java.text.NumberFormat
import java.util.Locale

data class DashboardUiState(
    val userName: String = "",
    val budget: BudgetSummary = BudgetSummary(),
    val alert: AlertSummary? = null,
    val trend: TrendSummary = TrendSummary(),
    val insight: String? = null,
    val categories: List<CategorySummary> = emptyList(),
    val recentActivity: List<TransactionSummary> = emptyList(),
    val isLoading: Boolean = false
)

data class BudgetSummary(
    val limit: Double = 0.0,
    val spent: Double = 0.0,
    val totalIncome: Double = 0.0,
    val totalExpenses: Double = 0.0
) {
    val remaining: Double get() = limit - spent
    val percentageUsed: Float get() = if (limit > 0) (spent / limit).toFloat() else 0f
}

data class AlertSummary(
    val message: String,
    val actionText: String
)

data class TrendSummary(
    val percentageChange: Double = 0.0,
    val isUp: Boolean = true,
    val period: String = "Last 4 weeks"
)

data class CategorySummary(
    val name: String,
    val amount: Double,
    val percentage: Float
)

data class TransactionSummary(
    val id: String,
    val title: String,
    val category: String,
    val date: String,
    val amount: Double,
    val isIncome: Boolean
)

private val phFormatter = NumberFormat.getCurrencyInstance(Locale("en", "PH"))
fun formatCurrency(amount: Double): String = phFormatter.format(amount)

@Composable
fun DashboardScreen(
    state: DashboardUiState = DashboardUiState(),
    onDisplayNameChange: (String) -> Unit = {},
    onManageBudgetClick: () -> Unit = {} // NEW: Callback for navigation
) {
    var isEditingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf(state.userName) }

    LaunchedEffect(state.userName) {
        nameDraft = state.userName
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA)),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 50.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            GreetingSection(
                userName = state.userName,
                onNameClick = { isEditingName = true },
            )
        }

        item {
            BudgetCard(budget = state.budget, onManageClick = onManageBudgetClick)
        }

        if (state.alert != null) {
            item {
                AlertCard(alert = state.alert)
            }
        }

        item {
            TrendCard(trend = state.trend)
        }

        item {
            InsightCard(insightText = state.insight)
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            CategoriesSection(categories = state.categories)
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            RecentActivitySection(transactions = state.recentActivity)
        }
    }

    if (isEditingName) {
        AlertDialog(
            onDismissRequest = { isEditingName = false },
            title = { Text("Edit name") },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it.take(80) },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDisplayNameChange(nameDraft)
                        isEditingName = false
                    },
                    enabled = nameDraft.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { isEditingName = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun GreetingSection(
    userName: String,
    onNameClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Good afternoon",
                color = Color(0xFF757575),
                fontSize = 14.sp
            )
            Text(
                text = userName.ifBlank { "User" },
                color = Color(0xFF1F1F1F),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onNameClick),
            )
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0xFFE0E0E0)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.bell),
                contentDescription = "Notifications",
                modifier = Modifier.size(22.dp),
                tint = Color(0xFF1F1F1F)
            )
        }
    }
}

@Composable
private fun BudgetCard(
    budget: BudgetSummary,
    onManageClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF00A86B)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp) // Optimized elevation
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Monthly budget", color = Color.White, fontSize = 14.sp)

                // NEW: Clickable Manage Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onManageClick() }
                        .padding(4.dp)
                ) {
                    Text(text = "Manage", color = Color.White, fontSize = 14.sp)
                    Icon(
                        painter = painterResource(id = R.drawable.chevron_right),
                        contentDescription = "Manage",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = formatCurrency(budget.remaining),
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "left",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
                )
            }

            LinearProgressIndicator(
                progress = { budget.percentageUsed },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .padding(top = 16.dp),
                color = Color.White,
                trackColor = Color(0x4DFFFFFF),
                strokeCap = StrokeCap.Round
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Spent ${formatCurrency(budget.spent)} of ${formatCurrency(budget.limit)}",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Text(
                    text = "${(budget.percentageUsed * 100).toInt()}% used",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2EBA86))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Income", color = Color(0xFFE0F2F1), fontSize = 12.sp)
                        Text(
                            text = formatCurrency(budget.totalIncome),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2EBA86))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Expenses", color = Color(0xFFE0F2F1), fontSize = 12.sp)
                        Text(
                            text = formatCurrency(budget.totalExpenses),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertCard(alert: AlertSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.triangle_alert),
                contentDescription = "Alert",
                tint = Color(0xFFF57F17),
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = alert.message,
                color = Color(0xFF795548),
                fontSize = 14.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            )
            Text(
                text = alert.actionText,
                color = Color(0xFFD84315),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TrendCard(trend: TrendSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Spending trend", color = Color(0xFF1F1F1F), fontSize = 18.sp)
                    Text(
                        text = trend.period,
                        color = Color(0xFF9E9E9E),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = if (trend.isUp) R.drawable.trending_up else R.drawable.trending_down),
                        contentDescription = "Trend",
                        tint = if (trend.isUp) Color(0xFFD32F2F) else Color(0xFF00A86B),
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 4.dp)
                    )
                    Text(
                        text = "${trend.percentageChange}% vs prev",
                        color = if (trend.isUp) Color(0xFFD32F2F) else Color(0xFF00A86B),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(top = 24.dp)
                    .background(Color(0xFFE8F5E9))
            )
        }
    }
}

@Composable
private fun InsightCard(insightText: String?) {
    val isDataEmpty = insightText.isNullOrBlank()
    val displayColor = if (isDataEmpty) Color(0xFF9E9E9E) else Color(0xFF1F1F1F)
    val cardColor = if (isDataEmpty) Color.White else Color(0xFFF5F3FF)
    val iconBgColor = if (isDataEmpty) Color(0xFFF5F5F5) else Color.White

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDataEmpty) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconBgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.astroid),
                    contentDescription = "Insight",
                    tint = if (isDataEmpty) Color(0xFFBDBDBD) else Color(0xFF7F22FE),
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = "Insight",
                    color = if (isDataEmpty) Color(0xFF9E9E9E) else Color(0xFF7F22FE),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = insightText ?: "No insights yet. Keep tracking your spending to unlock AI analysis.",
                    color = displayColor,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (!isDataEmpty) {
                    Text(
                        text = "Powered by AI",
                        color = Color(0xFF9E9E9E),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoriesSection(categories: List<CategorySummary>) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Categories", color = Color(0xFF1F1F1F), fontSize = 16.sp)
            if (categories.isNotEmpty()) {
                Text(text = "Manage", color = Color(0xFF00A86B), fontSize = 14.sp)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            if (categories.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(Color(0xFFF5F5F5), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "0%",
                            color = Color(0xFFBDBDBD),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "No spending data available yet.",
                        color = Color(0xFF9E9E9E),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(Color(0xFFEEEEEE), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "100%",
                            color = Color(0xFF1F1F1F),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(
                        modifier = Modifier
                            .padding(start = 24.dp)
                            .weight(1f)
                    ) {
                        categories.forEach { category ->
                            CategoryItem(name = category.name, amount = formatCurrency(category.amount))
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryItem(name: String, amount: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "• $name", color = Color(0xFF424242), fontSize = 14.sp)
        Text(text = amount, color = Color(0xFF757575), fontSize = 14.sp)
    }
}

@Composable
private fun RecentActivitySection(transactions: List<TransactionSummary>) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Recent activity", color = Color(0xFF1F1F1F), fontSize = 16.sp)
            if (transactions.isNotEmpty()) {
                Text(text = "See all", color = Color(0xFF00A86B), fontSize = 14.sp)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            if (transactions.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.receipt),
                        contentDescription = "No receipts",
                        tint = Color(0xFFE0E0E0),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "No recent activity.",
                        color = Color(0xFF757575),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        text = "Tap the + button to add your first transaction!",
                        color = Color(0xFF9E9E9E),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            } else {
                Column {
                    transactions.forEachIndexed { index, transaction ->
                        TransactionItem(transaction = transaction)
                        if (index < transactions.lastIndex) {
                            HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionItem(transaction: TransactionSummary) {
    val iconData = remember(transaction.category) {
        when (transaction.category.lowercase()) {
            "food" -> Pair(R.drawable.utensils, Color(0xFF4CAF50))
            "transport" -> Pair(R.drawable.car, Color(0xFF009688))
            "income", "salary" -> Pair(R.drawable.hand_coins, Color(0xFF00A86B))
            "shopping" -> Pair(R.drawable.shopping_basket, Color(0xFFF57F17))
            "entertainment" -> Pair(R.drawable.film, Color(0xFF673AB7))
            "bills" -> Pair(R.drawable.banknote_arrow_up, Color(0xFFD32F2F))
            "health" -> Pair(R.drawable.heart_pulse, Color(0xFFE91E63))
            else -> Pair(R.drawable.receipt, Color(0xFF757575))
        }
    }

    val iconResId = iconData.first
    val iconTint = iconData.second
    val iconBgColor = remember(iconTint) { iconTint.copy(alpha = 0.1f) }

    val amountPrefix = if (transaction.isIncome) "+" else "-"
    val amountColor = if (transaction.isIncome) Color(0xFF00A86B) else Color(0xFF1F1F1F)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = iconResId),
                contentDescription = transaction.category,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(text = transaction.title, color = Color(0xFF1F1F1F), fontSize = 16.sp)
            Text(text = "${transaction.category} · ${transaction.date}", color = Color(0xFF757575), fontSize = 12.sp)
        }

        Text(
            text = "$amountPrefix${formatCurrency(transaction.amount)}",
            color = amountColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}