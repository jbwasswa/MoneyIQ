package ug.bondwise.app

import android.app.DatePickerDialog
import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.math.BigDecimal
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch
import kotlin.math.pow

private val Navy = Color(0xFF17365D)
private val Teal = Color(0xFF0F8B8D)
private val Canvas = Color(0xFFF3F6FA)
private val Ink = Color(0xFF172B4D)
private val Muted = Color(0xFF6B7B90)
private val Line = Color(0xFFE0E7EF)
private val DisplayDateFormat = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
private val StorageDateFormat = DateTimeFormatter.ISO_LOCAL_DATE
private const val PREFS = "bondwise_local"
private const val BONDS_KEY = "saved_bonds"
private val PremiumRed = Color(0xFFB54747)
private val DiscountGreen = Color(0xFF16835D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BondWiseApp() }
    }
}

data class PurchaseLot(
    val id: String = UUID.randomUUID().toString(),
    val purchaseDate: LocalDate,
    val faceValue: Double,
    val cleanPrice: Double,
    val purchaseDateConfirmed: Boolean = true
)

data class Bond(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val faceValue: Double,
    val couponRate: Double,
    val cleanPrice: Double,
    val maturityDate: LocalDate,
    val nextCouponDate: LocalDate,
    val frequency: Int,
    val taxRate: Double,
    val lots: List<PurchaseLot> = emptyList()
) {
    val purchaseLots: List<PurchaseLot> get() = lots.ifEmpty { listOf(PurchaseLot(id = "legacy-$id", purchaseDate = LocalDate.now(), faceValue = faceValue, cleanPrice = cleanPrice, purchaseDateConfirmed = false)) }
    val totalFaceValue: Double get() = purchaseLots.sumOf { it.faceValue }
    val totalCleanPrice: Double get() = purchaseLots.sumOf { it.cleanPrice }
    val totalCashOutlay: Double get() = totalCleanPrice
    val annualGrossCoupon: Double get() = totalFaceValue * couponRate / 100.0
    val annualNetCoupon: Double get() = annualGrossCoupon * (1.0 - taxRate / 100.0)
    val grossCurrentYield: Double get() = if (totalCleanPrice > 0) annualGrossCoupon / totalCleanPrice * 100.0 else 0.0
    val netCurrentYield: Double get() = if (totalCleanPrice > 0) annualNetCoupon / totalCleanPrice * 100.0 else 0.0
}

private fun Bond.toForm(): BondForm {
    val firstLot = purchaseLots.first()
    return BondForm(id = id, name = name, face = totalFaceValue.grouped(), coupon = couponRate.toString(), price = totalCleanPrice.grouped(), tax = taxRate.toString(), frequency = frequency, nextCouponDate = nextCouponDate, maturityDate = maturityDate, purchaseDate = firstLot.purchaseDate, purchaseDateConfirmed = firstLot.purchaseDateConfirmed)
}

data class Cashflow(
    val date: LocalDate,
    val grossCoupon: Double,
    val tax: Double,
    val netCoupon: Double,
    val principal: Double
)

data class PricePosition(val label: String, val detail: String, val color: Color)

data class BondForm(
    val id: String? = null,
    val name: String = "",
    val face: String = "",
    val coupon: String = "",
    val price: String = "",
    val tax: String = "10",
    val frequency: Int = 2,
    val nextCouponDate: LocalDate? = null,
    val maturityDate: LocalDate? = null,
    val purchaseDate: LocalDate = LocalDate.now(),
    val purchaseDateConfirmed: Boolean = true
) {
    fun toBond(): Bond? {
        val faceValue = face.toAmountOrNull() ?: return null
        val couponRate = coupon.toDoubleOrNull() ?: return null
        val cleanPrice = price.toAmountOrNull() ?: return null
        val taxRate = tax.toDoubleOrNull() ?: return null
        val nextCouponDate = nextCouponDate ?: return null
        val maturityDate = maturityDate ?: return null
        if (name.isBlank() || faceValue <= 0 || couponRate < 0 || cleanPrice <= 0 || taxRate !in 0.0..100.0) return null
        if (frequency !in listOf(1, 2) || nextCouponDate.isBefore(LocalDate.now()) || !nextCouponDate.isBefore(maturityDate)) return null
        if (purchaseDate.isAfter(LocalDate.now()) || !purchaseDate.isBefore(maturityDate)) return null
        val lot = PurchaseLot(purchaseDate = purchaseDate, faceValue = faceValue, cleanPrice = cleanPrice, purchaseDateConfirmed = purchaseDateConfirmed)
        return Bond(
            id = id ?: UUID.randomUUID().toString(), name = name.trim(), faceValue = faceValue, couponRate = couponRate, cleanPrice = cleanPrice,
            maturityDate = maturityDate, nextCouponDate = nextCouponDate, frequency = frequency, taxRate = taxRate, lots = listOf(lot)
        )
    }
}

private fun BondForm.validationMessage(): String {
    val missingDates = listOfNotNull(
        if (nextCouponDate == null) "Next coupon date" else null,
        if (maturityDate == null) "Maturity date" else null
    )
    if (missingDates.isNotEmpty()) {
        return if (missingDates.size == 1) "${missingDates.first()} is required." else "${missingDates.joinToString(" and ")} are required."
    }
    return "Check the required values. Next coupon must be today or later and before maturity."
}

data class PurchaseLotForm(
    val purchaseDate: LocalDate = LocalDate.now(),
    val faceValue: String = "",
    val cleanPrice: String = "",
    val id: String? = null,
    val purchaseDateConfirmed: Boolean = true
) {
    fun toLot(maturityDate: LocalDate): PurchaseLot? {
        val face = faceValue.toAmountOrNull() ?: return null
        val price = cleanPrice.toAmountOrNull() ?: return null
        if (face <= 0 || price <= 0 || purchaseDate.isAfter(LocalDate.now()) || !purchaseDate.isBefore(maturityDate)) return null
        return PurchaseLot(id = id ?: UUID.randomUUID().toString(), purchaseDate = purchaseDate, faceValue = face, cleanPrice = price, purchaseDateConfirmed = purchaseDateConfirmed)
    }
}

private fun PurchaseLot.toForm() = PurchaseLotForm(
    purchaseDate = purchaseDate,
    faceValue = faceValue.grouped(),
    cleanPrice = cleanPrice.grouped(),
    id = id,
    purchaseDateConfirmed = purchaseDateConfirmed
)

@Composable
fun BondWiseApp() {
    val context = LocalContext.current
    val bonds = remember { mutableStateListOf<Bond>().apply { addAll(loadBonds(context)) } }
    val pages = listOf("Overview", "Compare", "Calculator", "Coupons", "My bonds")
    var page by remember { mutableStateOf("Overview") }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var editingBond by remember { mutableStateOf<Bond?>(null) }
    var scheduledBond by remember { mutableStateOf<Bond?>(null) }
    var lotsBond by remember { mutableStateOf<Bond?>(null) }
    var purchaseBond by remember { mutableStateOf<Bond?>(null) }
    var purchasePendingEdit by remember { mutableStateOf<Pair<Bond, PurchaseLot>?>(null) }
    var bondPendingDeletion by remember { mutableStateOf<Bond?>(null) }
    var purchasePendingDeletion by remember { mutableStateOf<Pair<Bond, PurchaseLot>?>(null) }
    var showExitConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settledPage ->
            page = pages[settledPage]
        }
    }

    fun save() = persistBonds(context, bonds)
    LaunchedEffect(Unit) { save() }
    fun addBond(bond: Bond) { bonds.add(bond); save() }
    fun removeBond(bond: Bond) { bonds.removeAll { it.id == bond.id }; save() }
    fun updateBond(bond: Bond) { val index = bonds.indexOfFirst { it.id == bond.id }; if (index >= 0) { bonds[index] = bond; save(); if (lotsBond?.id == bond.id) lotsBond = bond } }
    fun addPurchase(bondId: String, lot: PurchaseLot) {
        val bond = bonds.firstOrNull { it.id == bondId } ?: return
        updateBond(bond.copy(lots = bond.purchaseLots + lot, faceValue = bond.totalFaceValue + lot.faceValue, cleanPrice = bond.totalCleanPrice + lot.cleanPrice))
    }
    fun removePurchase(bondId: String, lotId: String) {
        val bond = bonds.firstOrNull { it.id == bondId } ?: return
        val remaining = bond.purchaseLots.filterNot { it.id == lotId }
        if (remaining.isNotEmpty()) updateBond(bond.copy(lots = remaining, faceValue = remaining.sumOf { it.faceValue }, cleanPrice = remaining.sumOf { it.cleanPrice }))
    }
    fun updatePurchase(bondId: String, updatedLot: PurchaseLot) {
        val bond = bonds.firstOrNull { it.id == bondId } ?: return
        val updatedLots = bond.purchaseLots.map { if (it.id == updatedLot.id) updatedLot else it }
        updateBond(bond.copy(lots = updatedLots, faceValue = updatedLots.sumOf { it.faceValue }, cleanPrice = updatedLots.sumOf { it.cleanPrice }))
    }

    BackHandler {
        when {
            showExitConfirmation -> showExitConfirmation = false
            purchasePendingDeletion != null -> purchasePendingDeletion = null
            bondPendingDeletion != null -> bondPendingDeletion = null
            purchasePendingEdit != null -> purchasePendingEdit = null
            purchaseBond != null -> purchaseBond = null
            lotsBond != null -> lotsBond = null
            scheduledBond != null -> scheduledBond = null
            editingBond != null -> editingBond = null
            showAdd -> showAdd = false
            showHelp -> showHelp = false
            page != "Overview" || pagerState.isScrollInProgress -> scope.launch { pagerState.animateScrollToPage(0) }
            else -> showExitConfirmation = true
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, secondary = Navy, background = Canvas, surface = Color.White, onSurface = Ink)) {
        Scaffold(
            containerColor = Canvas,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    pages.forEach { item ->
                        NavigationBarItem(
                            selected = page == item,
                            onClick = {
                                val target = pages.indexOf(item)
                                if (target >= 0) scope.launch { pagerState.animateScrollToPage(target) }
                            },
                            icon = { Text(when (item) { "Overview" -> "⌂"; "Compare" -> "⇄"; "Calculator" -> "▦"; "Coupons" -> "◷"; else -> "▤" }, fontSize = 19.sp) },
                            label = { Text(when (item) { "Overview" -> "Home"; "Calculator" -> "Calc"; "My bonds" -> "Bonds"; else -> item }, fontSize = 10.sp, maxLines = 1) }
                        )
                    }
                }
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                Header(onHelp = { showHelp = true })
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { pageIndex ->
                    when (pages[pageIndex]) {
                        "Overview" -> Overview(bonds, onCompare = { scope.launch { pagerState.animateScrollToPage(1) } }, onAdd = { showAdd = true }, onSchedule = { scheduledBond = it }, onLots = { lotsBond = it }, onAddPurchase = { purchaseBond = it })
                        "Compare" -> Compare(bonds, { showAdd = true }, { scheduledBond = it }, { editingBond = it }, { bondPendingDeletion = it }, { lotsBond = it }, { purchaseBond = it })
                        "Calculator" -> Calculator(onSave = ::addBond, onSchedule = { scheduledBond = it })
                        "Coupons" -> Coupons(bonds)
                        else -> Portfolio(bonds, { scheduledBond = it }, { editingBond = it }, { bondPendingDeletion = it }, { showAdd = true }, { lotsBond = it }, { purchaseBond = it })
                    }
                }
            }
        }
        if (showAdd) AddBondDialog(onDismiss = { showAdd = false }, onAdd = { addBond(it); showAdd = false })
        editingBond?.let { bond -> EditBondDialog(bond, onDismiss = { editingBond = null }, onSave = { updated ->
            val updatedLots = if (bond.purchaseLots.size == 1) updated.purchaseLots.map { it.copy(id = bond.purchaseLots.first().id) } else bond.purchaseLots
            updateBond(updated.copy(lots = updatedLots, faceValue = updatedLots.sumOf { it.faceValue }, cleanPrice = updatedLots.sumOf { it.cleanPrice }))
            editingBond = null
        }) }
        scheduledBond?.let { ScheduleDialog(it, onDismiss = { scheduledBond = null }) }
        if (showHelp) HelpDialog(onDismiss = { showHelp = false })
        lotsBond?.let { bond -> PurchaseLotsDialog(bond, onDismiss = { lotsBond = null }, onAdd = { lotsBond = null; purchaseBond = bond }, onEdit = { lot -> lotsBond = null; purchasePendingEdit = bond to lot }, onRemove = { lot -> purchasePendingDeletion = bond to lot }) }
        purchaseBond?.let { bond -> PurchaseLotDialog(bond, PurchaseLotForm(), "Add purchase lot", "Save purchase", onDismiss = { purchaseBond = null }, onSave = { lot -> addPurchase(bond.id, lot); purchaseBond = null }) }
        purchasePendingEdit?.let { (bond, lot) -> PurchaseLotDialog(bond, lot.toForm(), "Edit purchase", "Save changes", onDismiss = { purchasePendingEdit = null }, onSave = { updated -> updatePurchase(bond.id, updated); purchasePendingEdit = null; lotsBond = bonds.firstOrNull { it.id == bond.id } }) }
        bondPendingDeletion?.let { bond -> ConfirmDeleteDialog("Delete bond?", "${bond.name} and all its purchase records will be permanently removed.", onDismiss = { bondPendingDeletion = null }, onConfirm = { removeBond(bond); bondPendingDeletion = null }) }
        purchasePendingDeletion?.let { (bond, lot) -> ConfirmDeleteDialog("Delete purchase?", "This purchase record (${money(lot.cleanPrice)}) will be permanently removed from ${bond.name}.", onDismiss = { purchasePendingDeletion = null }, onConfirm = { removePurchase(bond.id, lot.id); purchasePendingDeletion = null; lotsBond = bonds.firstOrNull { it.id == bond.id } }) }
        if (showExitConfirmation) AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text("Exit BondWise?") },
            text = { Text("Are you sure you want to exit the app?") },
            confirmButton = { TextButton(onClick = { showExitConfirmation = false; (context as? Activity)?.finish() }) { Text("Exit", color = PremiumRed) } },
            dismissButton = { TextButton(onClick = { showExitConfirmation = false }) { Text("Stay") } }
        )
    }
}

@Composable private fun Header(onHelp: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Navy).padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("▥", color = Color(0xFF6DE0D1), fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("BondWise UG", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Compare bonds and coupon income", color = Color(0xFFD5E4EF), fontSize = 11.sp)
        }
        TextButton(onClick = onHelp) { Text("? Help", color = Color.White, fontSize = 12.sp) }
    }
}

@Composable private fun Overview(bonds: List<Bond>, onCompare: () -> Unit, onAdd: () -> Unit, onSchedule: (Bond) -> Unit, onLots: (Bond) -> Unit, onAddPurchase: (Bond) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Portfolio overview", if (bonds.size == 1) "1 BOND" else "${bonds.size} BONDS") }
        if (bonds.isEmpty()) {
            item {
                CardBox {
                    Text("Your portfolio is ready to build", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Track the bonds you own, purchase amounts, face value and upcoming coupon income.", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("+ Add your first bond") }
                }
            }
        } else {
            val best = bonds.maxByOrNull { estimatedReturn(it, afterTax = true) ?: Double.NEGATIVE_INFINITY }
            val nextCoupon = bonds.flatMap { bond -> couponSchedule(bond).filter { it.grossCoupon > 0 }.map { ScheduledCoupon(bond, it) } }.minByOrNull { it.cashflow.date }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardBox {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Face value held", color = Muted, fontSize = 10.sp)
                                Text(money(bonds.sumOf { it.totalFaceValue }), color = Teal, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Annual net coupons", color = Muted, fontSize = 10.sp)
                                Text(money(bonds.sumOf { it.annualNetCoupon }), color = Teal, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("Bonds tracked", bonds.size.toString(), Modifier.weight(1f))
                        MetricCard("Total purchase price", money(bonds.sumOf { it.totalCleanPrice }), Modifier.weight(1f))
                    }
                }
            }
            if (nextCoupon != null) {
                item {
                    CardBox {
                        Text("Next coupon", color = Muted, fontSize = 11.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(nextCoupon.bond.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("${nextCoupon.cashflow.date.format(DisplayDateFormat)} · ${daysRemaining(nextCoupon.cashflow.date)}", color = Teal, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                            Text(money(nextCoupon.cashflow.netCoupon), color = Teal, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
            item {
                CardBox {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Highest ${best?.let { netReturnLabel(it) } ?: "estimated net return"}", color = Muted, fontSize = 11.sp)
                            Text(best?.name ?: "—", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        Text(best?.let { estimatedReturn(it, true)?.fmt()?.plus("%") } ?: "—", color = Teal, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
            item { SectionTitle("Your bonds") }
            items(bonds.sortedByDescending { estimatedReturn(it, true) ?: Double.NEGATIVE_INFINITY }.take(3), key = { it.id }) { bond ->
                BondCard(bond, onSchedule = { onSchedule(bond) }, onEdit = null, onRemove = null, onLots = { onLots(bond) }, onAddPurchase = { onAddPurchase(bond) })
            }
            item { Button(onClick = onCompare, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Compare bonds") } }
            item { OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("+ Add another bond") } }
        }
    }
}

@Composable private fun Compare(
    bonds: List<Bond>, onAdd: () -> Unit,
    onSchedule: (Bond) -> Unit, onEdit: (Bond) -> Unit, onRemove: (Bond) -> Unit,
    onLots: (Bond) -> Unit, onAddPurchase: (Bond) -> Unit
) {
    var compareMode by remember { mutableStateOf("Saved") }
    var firstForm by remember { mutableStateOf(BondForm(name = "Bond A")) }
    var secondForm by remember { mutableStateOf(BondForm(name = "Bond B")) }
    if (compareMode == "Quick compare") {
        QuickCompare(firstForm, { firstForm = it }, secondForm, { secondForm = it }, onSavedBonds = { compareMode = "Saved" })
        return
    }
    val sorted = bonds.sortedWith(compareBy<Bond> { it.purchaseLots.minOf { lot -> lot.purchaseDate } }.thenBy { it.name.lowercase(Locale.ROOT) })
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionTitle("Compare bonds", "${bonds.size} SAVED")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = true, onClick = { compareMode = "Saved" }, label = { Text("Saved bonds") })
                FilterChip(selected = false, onClick = { compareMode = "Quick compare" }, label = { Text("Quick compare") })
            }
        }
        if (sorted.isEmpty()) {
            item { CardBox { Text("No bonds saved yet", fontWeight = FontWeight.Bold); Text("Add a bond to see its estimated yield and coupon schedule here.", color = Muted, fontSize = 12.sp); Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add a bond") } } }
        } else {
            items(sorted, key = { it.id }) { bond -> BondCard(bond, collapsible = false, onSchedule = { onSchedule(bond) }, onEdit = { onEdit(bond) }, onRemove = { onRemove(bond) }, onLots = { onLots(bond) }, onAddPurchase = { onAddPurchase(bond) }) }
            item { OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("+ Add a bond") } }
        }
    }
}

@Composable private fun QuickCompare(
    first: BondForm, onFirst: (BondForm) -> Unit,
    second: BondForm, onSecond: (BondForm) -> Unit,
    onSavedBonds: () -> Unit
) {
    val firstBond = first.toBond()
    val secondBond = second.toBond()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionTitle("Quick compare", "NOT SAVED")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = false, onClick = onSavedBonds, label = { Text("Saved bonds") })
                FilterChip(selected = true, onClick = {}, label = { Text("Quick compare") })
            }
            Text("Enter two bonds to compare their estimated yields and coupon income. These entries stay here and are not added to My bonds.", color = Muted, fontSize = 12.sp)
        }
        item { Text("Bond A", color = Teal, fontWeight = FontWeight.Bold); BondFields(first, onFirst) }
        item { Text("Bond B", color = Teal, fontWeight = FontWeight.Bold); BondFields(second, onSecond) }
        if (firstBond != null && secondBond != null) {
            item {
                CardBox {
                    Text("Side-by-side results", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    CompareResultHeader(firstBond.name, secondBond.name)
                    CompareResultRow("Coupon rate", "${firstBond.couponRate.fmt()}%", "${secondBond.couponRate.fmt()}%")
                    CompareResultRow("Face Value", money(firstBond.totalFaceValue), money(secondBond.totalFaceValue))
                    CompareResultRow("Purchase Price", money(firstBond.totalCleanPrice), money(secondBond.totalCleanPrice))
                    ComparePricePositionRow(firstBond, secondBond)
                    CompareResultRow(if (firstBond.purchaseLots.size > 1 || secondBond.purchaseLots.size > 1) "Estimated Net Return" else "Estimated Net YTM", "${estimatedReturn(firstBond, true)?.fmt() ?: "—"}%", "${estimatedReturn(secondBond, true)?.fmt() ?: "—"}%")
                    CompareResultRow(if (firstBond.purchaseLots.size > 1 || secondBond.purchaseLots.size > 1) "Estimated Gross Return" else "Estimated Gross YTM", "${estimatedReturn(firstBond, false)?.fmt() ?: "—"}%", "${estimatedReturn(secondBond, false)?.fmt() ?: "—"}%")
                    CompareResultRow("Net annual coupon", money(firstBond.annualNetCoupon), money(secondBond.annualNetCoupon))
                    CompareResultRow("Net current yield", "${firstBond.netCurrentYield.fmt()}%", "${secondBond.netCurrentYield.fmt()}%")
                    CompareResultRow("Maturity", firstBond.maturityDate.format(DisplayDateFormat), secondBond.maturityDate.format(DisplayDateFormat))
                    Spacer(Modifier.height(8.dp))
                    val aReturn = estimatedReturn(firstBond, true)
                    val bReturn = estimatedReturn(secondBond, true)
                    val lead = when {
                        aReturn == null || bReturn == null -> "Return cannot be estimated for one or both entries with these inputs."
                        aReturn > bReturn -> "${firstBond.name} has the higher estimated net return."
                        bReturn > aReturn -> "${secondBond.name} has the higher estimated net return."
                        else -> "Both have the same estimated net return."
                    }
                    Text(lead, color = Teal, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("This comparison uses the total purchase prices you entered. It does not apply market-specific day-count, settlement or coupon-entitlement conventions.", color = Muted, fontSize = 10.sp)
                }
            }
        } else {
            item { CardBox { Text("Complete both bond entries to see the comparison.", color = Muted, fontSize = 12.sp) } }
        }
    }
}

@Composable private fun CompareResultHeader(firstName: String, secondName: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1.2f))
        Column(Modifier.weight(1f)) {
            Text("BOND A", color = Teal, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            Text(firstName, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 2)
        }
        Column(Modifier.weight(1f)) {
            Text("BOND B", color = Teal, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            Text(secondName, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 2)
        }
    }
}

@Composable private fun CompareResultRow(label: String, first: String, second: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1.2f), color = Muted, fontSize = 11.sp)
        Text(first, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        Text(second, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    }
}

@Composable private fun ComparePricePositionRow(first: Bond, second: Bond) {
    val firstPosition = premiumDiscount(first)
    val secondPosition = premiumDiscount(second)
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Price vs face", Modifier.weight(1.2f), color = Muted, fontSize = 11.sp)
        PricePositionCell(firstPosition, Modifier.weight(1f))
        PricePositionCell(secondPosition, Modifier.weight(1f))
    }
}

@Composable private fun PricePositionCell(position: PricePosition, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(position.label, color = position.color, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        Text(position.detail, color = position.color, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
    }
}

@Composable private fun Calculator(onSave: (Bond) -> Unit, onSchedule: (Bond) -> Unit) {
    var form by remember { mutableStateOf(BondForm()) }
    val bond = form.toBond()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Bond calculator", "YOUR INPUTS") }
        item { BondFields(form, { form = it }) }
        if (bond != null) {
            item {
                CardBox {
                    Text("Annual coupon income", color = Muted, fontSize = 12.sp)
                    Text(money(bond.annualGrossCoupon), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("After ${bond.taxRate.fmt()}% tax: ${money(bond.annualNetCoupon)} per year", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("Gross current yield", "${bond.grossCurrentYield.fmt()}%", Modifier.weight(1f))
                        MetricCard("Net current yield", "${bond.netCurrentYield.fmt()}%", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard(if (bond.purchaseLots.size > 1) "Estimated Gross Return" else "Estimated Gross YTM", "${estimatedReturn(bond, false)?.fmt() ?: "—"}%", Modifier.weight(1f))
                        MetricCard(netReturnLabel(bond), "${estimatedReturn(bond, true)?.fmt() ?: "—"}%", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Dated cash-flow estimate using the total purchase price you enter for each purchase.", fontSize = 11.sp, color = Muted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { onSchedule(bond) }, modifier = Modifier.fillMaxWidth()) { Text("Preview coupon schedule") }
                    Button(onClick = { onSave(bond) }, modifier = Modifier.fillMaxWidth()) { Text("Save to My bonds") }
                }
            }
        }
    }
}

@Composable private fun Portfolio(bonds: List<Bond>, onSchedule: (Bond) -> Unit, onEdit: (Bond) -> Unit, onRemove: (Bond) -> Unit, onAdd: () -> Unit, onLots: (Bond) -> Unit, onAddPurchase: (Bond) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("My bonds") }
        if (bonds.isEmpty()) {
            item { CardBox { Text("Your bond list is empty", fontWeight = FontWeight.Bold); Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add a bond") } } }
        } else {
            items(bonds, key = { it.id }) { bond -> BondCard(bond, onSchedule = { onSchedule(bond) }, onEdit = { onEdit(bond) }, onRemove = { onRemove(bond) }, onLots = { onLots(bond) }, onAddPurchase = { onAddPurchase(bond) }) }
            item { OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("+ Add a bond") } }
        }
    }
}

@Composable private fun BondFields(form: BondForm, onChange: (BondForm) -> Unit, includePurchaseDetails: Boolean = true) {
    CardBox {
        TextFieldBlock("Bond name", form.name, { onChange(form.copy(name = it)) }, KeyboardType.Text)
        if (includePurchaseDetails) TextFieldBlock("Face Value", form.face, { onChange(form.copy(face = it)) }, KeyboardType.NumberPassword, groupThousands = true)
        TextFieldBlock("Annual coupon rate (%)", form.coupon, { onChange(form.copy(coupon = it)) }, KeyboardType.Decimal)
        if (includePurchaseDetails) {
            TextFieldBlock("Purchase Price (UGX)", form.price, { onChange(form.copy(price = it)) }, KeyboardType.NumberPassword, supporting = "Enter the total amount paid to the bank.", groupThousands = true)
            DateField("Purchase date", form.purchaseDate) { onChange(form.copy(purchaseDate = it, purchaseDateConfirmed = true)) }
        } else {
            Text("Purchase lots are unchanged here. Use Purchases to add or remove a tranche.", color = Muted, fontSize = 11.sp)
        }
        Text("Coupon frequency", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(2 to "Semi-annual", 1 to "Annual").forEach { (n, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = form.frequency == n, onClick = { onChange(form.copy(frequency = n)) }); Text(label, fontSize = 12.sp) }
            }
        }
        DateField("Next coupon date *", form.nextCouponDate) { onChange(form.copy(nextCouponDate = it)) }
        DateField("Maturity date *", form.maturityDate) { onChange(form.copy(maturityDate = it)) }
        TextFieldBlock("Coupon withholding tax (%)", form.tax, { onChange(form.copy(tax = it)) }, KeyboardType.Decimal)
    }
}

@Composable private fun AddBondDialog(onDismiss: () -> Unit, onAdd: (Bond) -> Unit) {
    var form by remember { mutableStateOf(BondForm()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add bond and first purchase") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Enter the bond terms and its first purchase lot. You can add later purchases from My bonds.", color = Muted, fontSize = 11.sp)
                BondFields(form, { form = it })
                if (error) Text(form.validationMessage(), color = PremiumRed, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { val bond = form.toBond(); if (bond == null) error = true else onAdd(bond) }) { Text("Save bond") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable private fun PurchaseLotDialog(bond: Bond, initialForm: PurchaseLotForm, title: String, saveLabel: String, onDismiss: () -> Unit, onSave: (PurchaseLot) -> Unit) {
    var form by remember(bond.id, initialForm.id) { mutableStateOf(initialForm) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(bond.name, color = Teal, fontWeight = FontWeight.Bold)
                DateField("Purchase date", form.purchaseDate) { form = form.copy(purchaseDate = it, purchaseDateConfirmed = true) }
                TextFieldBlock("Face value acquired (UGX)", form.faceValue, { form = form.copy(faceValue = it) }, KeyboardType.NumberPassword, groupThousands = true)
                TextFieldBlock("Purchase price (UGX)", form.cleanPrice, { form = form.copy(cleanPrice = it) }, KeyboardType.NumberPassword, supporting = "Enter the total amount paid to the bank.", groupThousands = true)
                if (error) Text("Enter positive face value and purchase price, and a purchase date that is not in the future or after maturity.", color = Color(0xFF9A5B00), fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { val lot = form.toLot(bond.maturityDate); if (lot == null) error = true else onSave(lot) }) { Text(saveLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable private fun PurchaseLotsDialog(bond: Bond, onDismiss: () -> Unit, onAdd: () -> Unit, onEdit: (PurchaseLot) -> Unit, onRemove: (PurchaseLot) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Purchases") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bond.name, color = Teal, fontWeight = FontWeight.Bold)
                Text("Combined face value: ${money(bond.totalFaceValue)}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text("Total purchase price: ${money(bond.totalCleanPrice)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                bond.purchaseLots.sortedBy { it.purchaseDate }.forEachIndexed { index, lot ->
                    CardBox {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                Text("Lot ${index + 1} · ${if (lot.purchaseDateConfirmed) lot.purchaseDate.format(DisplayDateFormat) else "Purchase date needs review"}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("Face value: ${money(lot.faceValue)}", fontSize = 11.sp)
                                Text("Purchase price: ${money(lot.cleanPrice)}", fontSize = 11.sp)
                                val difference = lot.cleanPrice - lot.faceValue
                                val label = when { kotlin.math.abs(difference) < 0.005 -> "At face value"; difference > 0 -> "Premium ${money(difference)}"; else -> "Discount ${money(-difference)}" }
                                val color = when { kotlin.math.abs(difference) < 0.005 -> Muted; difference > 0 -> PremiumRed; else -> DiscountGreen }
                                Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                TextButton(onClick = { onEdit(lot) }) { Text("Edit", fontSize = 11.sp) }
                                if (bond.purchaseLots.size > 1) TextButton(onClick = { onRemove(lot) }) { Text("Remove", color = Color(0xFF9A3D42), fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onAdd, shape = RoundedCornerShape(10.dp)) { Text("+ Add another purchase") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable private fun EditBondDialog(bond: Bond, onDismiss: () -> Unit, onSave: (Bond) -> Unit) {
    var form by remember(bond.id) { mutableStateOf(bond.toForm()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit bond") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            BondFields(form, { form = it }, includePurchaseDetails = bond.purchaseLots.size == 1)
            if (error) Text(form.validationMessage(), color = PremiumRed, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = { val updated = form.toBond(); if (updated == null) error = true else onSave(updated) }) { Text("Save changes") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun DateField(label: String, date: LocalDate?, onDate: (LocalDate) -> Unit) {
    val context = LocalContext.current
    Surface(color = Color(0xFFF5F8FB), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth().clickable {
        val initialDate = date ?: LocalDate.now()
        DatePickerDialog(context, { _, year, month, day -> onDate(LocalDate.of(year, month + 1, day)) }, initialDate.year, initialDate.monthValue - 1, initialDate.dayOfMonth).show()
    }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(label, fontSize = 10.sp, color = Muted); Text(date?.format(DisplayDateFormat) ?: "Select date", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (date == null) Muted else Ink) }
            Text("Choose date", fontSize = 11.sp, color = Teal, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun TextFieldBlock(label: String, value: String, onValue: (String) -> Unit, keyboard: KeyboardType, supporting: String? = null, groupThousands: Boolean = false) {
    val focusManager = LocalFocusManager.current
    val labelContent: @Composable () -> Unit = { Text(label, fontSize = 12.sp) }
    val supportingContent: (@Composable () -> Unit)? = supporting?.let { { Text(it, fontSize = 10.sp) } }
    if (groupThousands) {
        var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
        LaunchedEffect(value) {
            if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
        }
        OutlinedTextField(
            value = field,
            onValueChange = { entered ->
                val formatted = entered.text.replace(",", "").groupedInput()
                val digitsBeforeCursor = entered.text.take(entered.selection.start).count { it.isDigit() }
                var cursor = 0
                var digits = 0
                while (cursor < formatted.length && digits < digitsBeforeCursor) {
                    if (formatted[cursor].isDigit()) digits++
                    cursor++
                }
                field = TextFieldValue(formatted, TextRange(cursor.coerceAtMost(formatted.length)))
                onValue(formatted)
            },
            label = labelContent,
            singleLine = true,
            supportingText = supportingContent,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)
        )
    } else {
        OutlinedTextField(
            value = value, onValueChange = onValue, label = labelContent, singleLine = true,
            supportingText = supportingContent,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)
        )
    }
}

@Composable private fun BondCard(bond: Bond, isTop: Boolean = false, collapsible: Boolean = true, onSchedule: () -> Unit, onEdit: (() -> Unit)? = null, onRemove: (() -> Unit)?, onLots: (() -> Unit)? = null, onAddPurchase: (() -> Unit)? = null) {
    var expanded by remember(bond.id) { mutableStateOf(false) }
    val position = premiumDiscount(bond)
    CardBox {
        val purchaseDates = bond.purchaseLots.map { it.purchaseDate }.distinct().sorted()
        val hasUnconfirmedPurchaseDate = bond.purchaseLots.any { !it.purchaseDateConfirmed }
        val purchaseDateText = when {
            hasUnconfirmedPurchaseDate -> "Needs review · Edit bond"
            purchaseDates.size == 1 -> purchaseDates.first().format(DisplayDateFormat)
            else -> "${purchaseDates.first().format(DisplayDateFormat)} – ${purchaseDates.last().format(DisplayDateFormat)}"
        }
        Column(
            Modifier.fillMaxWidth().then(if (collapsible) Modifier.clickable(onClickLabel = if (expanded) "Hide bond details" else "Show bond details") { expanded = !expanded } else Modifier)
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(bond.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (isTop) Text("TOP BY SELECTED MEASURE", color = Teal, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("Coupon ${bond.couponRate.fmt()}% · Matures ${bond.maturityDate.format(DisplayDateFormat)}", color = Muted, fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${estimatedReturn(bond, true)?.fmt() ?: "—"}%", color = Teal, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text(netReturnLabel(bond), fontSize = 10.sp, color = Muted)
                }
            }
            Text(
                "Purchase date: $purchaseDateText  ·  Next coupon: ${bond.nextCouponDate.format(DisplayDateFormat)}",
                color = if (hasUnconfirmedPurchaseDate) PremiumRed else Muted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                MetricCard("Face Value", money(bond.totalFaceValue), Modifier.weight(1f))
                MetricCard("Annual net coupon", money(bond.annualNetCoupon), Modifier.weight(1f))
            }
            if (collapsible) {
                Text(if (expanded) "Hide details  ⌃" else "View details  ⌄", color = Teal, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.End).padding(top = 4.dp))
            }
        }
        if (expanded || !collapsible) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Purchase Price", money(bond.totalCleanPrice), Modifier.weight(1f))
                MetricCard(position.label, position.detail, Modifier.weight(1f), position.color, position.color)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 7.dp)) {
                MetricCard(if (bond.purchaseLots.size > 1) "Estimated Gross Return" else "Estimated Gross YTM", "${estimatedReturn(bond, false)?.fmt() ?: "—"}%", Modifier.weight(1f))
                MetricCard("Tax on coupons", "${bond.taxRate.fmt()}%", Modifier.weight(1f))
            }
            if (onLots != null || onAddPurchase != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    if (onLots != null) TextButton(onClick = onLots, modifier = Modifier.weight(1f)) { Text("Purchases (${bond.purchaseLots.size})") }
                    if (onAddPurchase != null) Button(onClick = onAddPurchase, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("+ Add purchase") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onSchedule, modifier = Modifier.weight(1.35f), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text("Schedule") }
                if (onEdit != null) TextButton(onClick = onEdit) { Text("Edit") }
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove", color = Color(0xFF9A3D42)) }
            }
        }
    }
}

@Composable private fun ScheduleDialog(bond: Bond, onDismiss: () -> Unit) {
    val schedule = remember(bond) { couponSchedule(bond) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Coupon schedule") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bond.name, fontWeight = FontWeight.Bold)
                Text("Coupon payments are scheduled from ${bond.nextCouponDate.format(DisplayDateFormat)} through maturity. Principal is returned at maturity.", fontSize = 11.sp, color = Muted)
                schedule.forEach { row ->
                    CardBox {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${row.date.format(DisplayDateFormat)} · ${daysRemaining(row.date)}", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                            Text(if (row.principal > 0) "${money(row.principal)} principal" else "Coupon", color = Teal, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        if (row.grossCoupon > 0) {
                            Text("Gross coupon: ${money(row.grossCoupon)}", fontSize = 11.sp)
                            Text("Tax withheld (${bond.taxRate.fmt()}%): ${money(row.tax)}", fontSize = 11.sp, color = Muted)
                            Text("Net coupon: ${money(row.netCoupon)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text("Schedule uses regular ${if (bond.frequency == 2) "six-month" else "annual"} periods. If maturity is between coupon dates, any stub coupon is not estimated; principal is shown on the maturity date.", fontSize = 10.sp, color = Muted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

private data class ScheduledCoupon(val bond: Bond, val cashflow: Cashflow)

@Composable private fun Coupons(bonds: List<Bond>) {
    val upcoming = bonds.flatMap { bond -> couponSchedule(bond).filter { it.grossCoupon > 0 }.map { ScheduledCoupon(bond, it) } }
        .sortedBy { it.cashflow.date }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("Coupons") }
        if (upcoming.isEmpty()) {
            item { CardBox { Text("No scheduled coupons", fontWeight = FontWeight.Bold); Text("Add a bond to see its upcoming coupon payments here.", color = Muted, fontSize = 12.sp) } }
        } else {
            items(upcoming) { payment ->
                CardBox {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(payment.bond.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${payment.cashflow.date.format(DisplayDateFormat)} · ${daysRemaining(payment.cashflow.date)}", color = Teal, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(money(payment.cashflow.netCoupon), color = Teal, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Net coupon", color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun daysRemaining(date: LocalDate): String {
    val days = ChronoUnit.DAYS.between(LocalDate.now(), date)
    return when {
        days == 0L -> "Today"
        days == 1L -> "1 day"
        days > 1L -> String.format(Locale.US, "%,d days", days)
        days == -1L -> "1 day ago"
        else -> String.format(Locale.US, "%,d days ago", -days)
    }
}

@Composable private fun ConfirmDeleteDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = PremiumRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("BondWise Help") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HelpSection("Tax", "The 10% tax value is an editable default, not a universal rate. Confirm the rate that applies to your bond and circumstances. BondWise applies the entered rate to coupon interest only, not principal repayment.")
                HelpSection("Estimated return", "For one purchase lot, the estimate is based on that lot’s purchase price, purchase date, maturity and coupon tax rate. For multiple lots, BondWise calculates a combined annualized return (XIRR) using each dated purchase price, projected coupon payments and principal at maturity. It is not a live market valuation or guaranteed return.")
                HelpSection("Multiple purchases", "Use one saved bond for all purchases of the same bond. Add each purchase with its own date, face value and total purchase price. The bond card totals the lots, while Purchases shows each lot and its premium or discount.")
                HelpSection("Purchase price", "Enter the total amount the bank charged for each purchase. Do not add accrued interest or fees separately; include the amount shown by the bank. The app does not apply market-specific day-count or settlement conventions.")
                HelpSection("Premium or discount", "BondWise compares Purchase Price with Face Value. A price above face value is shown as a red premium; a price below face value is shown as a green discount.")
                HelpSection("Compare bonds", "A higher estimated net return means a higher modeled return on the figures entered. Also consider maturity, coupon income, liquidity, and whether the bond suits your plans.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable private fun HelpSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(body, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Line), shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), content = content)
    }
}

@Composable private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Ink, labelColor: Color = Muted) {
    Surface(color = Color(0xFFF5F8FB), shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Column(Modifier.padding(10.dp)) { Text(label, color = labelColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold); Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
}

@Composable private fun SectionTitle(title: String, tag: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (tag != null) Surface(color = Color(0xFFE1F4F0), shape = RoundedCornerShape(30.dp)) { Text(tag, color = Teal, fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) }
    }
}

private fun couponSchedule(bond: Bond, fromDate: LocalDate = LocalDate.now()): List<Cashflow> {
    val periodMonths = 12 / bond.frequency
    val result = mutableListOf<Cashflow>()
    val anchor = bond.nextCouponDate
    val anchorDay = anchor.dayOfMonth
    fun dateAt(index: Long): LocalDate {
        val month = YearMonth.from(anchor).plusMonths(periodMonths.toLong() * index)
        return month.atDay(anchorDay.coerceAtMost(month.lengthOfMonth()))
    }
    var index = 0L
    while (dateAt(index).isBefore(fromDate) && index < 1000L) index++
    while (index > -1000L && !dateAt(index - 1).isBefore(fromDate)) index--
    var date = dateAt(index)
    while (!date.isAfter(bond.maturityDate) && index < 1000L) {
        val eligibleFace = bond.purchaseLots.filter { !it.purchaseDate.isAfter(date) }.sumOf { it.faceValue }
        val gross = eligibleFace * bond.couponRate / 100.0 / bond.frequency
        val tax = gross * bond.taxRate / 100.0
        result.add(Cashflow(date, gross, tax, gross - tax, if (date == bond.maturityDate) bond.totalFaceValue else 0.0))
        index++
        date = dateAt(index)
    }
    if (result.none { it.date == bond.maturityDate }) {
        result.add(Cashflow(bond.maturityDate, 0.0, 0.0, 0.0, bond.totalFaceValue))
    }
    return result.sortedBy { it.date }
}

private fun estimatedReturn(bond: Bond, afterTax: Boolean): Double? {
    val lots = bond.purchaseLots
    if (lots.isEmpty() || bond.maturityDate.isBefore(lots.maxOf { it.purchaseDate })) return null
    val earliestDate = lots.minOf { it.purchaseDate }
    val datedFlows = mutableListOf<Pair<LocalDate, Double>>()
    lots.forEach { lot -> datedFlows.add(lot.purchaseDate to -lot.cleanPrice) }
    couponSchedule(bond, earliestDate).forEach { flow ->
        val coupon = if (afterTax) flow.netCoupon else flow.grossCoupon
        if (coupon > 0) datedFlows.add(flow.date to coupon)
        if (flow.principal > 0) datedFlows.add(flow.date to flow.principal)
    }
    if (datedFlows.none { it.second > 0.0 }) return null
    val origin = datedFlows.minOf { it.first }
    fun npv(rate: Double): Double = datedFlows.sumOf { (date, amount) ->
        amount / (1.0 + rate).pow(java.time.temporal.ChronoUnit.DAYS.between(origin, date) / 365.25)
    }
    val brackets = mutableListOf<Pair<Double, Double>>()
    var previousRate = -0.99
    var previousNpv = npv(previousRate)
    val scanSteps = 800
    for (step in 1..scanSteps) {
        val rate = -0.99 + (5.99 * step / scanSteps)
        val value = npv(rate)
        if (previousNpv == 0.0) brackets.add(previousRate to previousRate)
        else if (value == 0.0 || (previousNpv < 0.0) != (value < 0.0)) brackets.add(previousRate to rate)
        previousRate = rate
        previousNpv = value
    }
    if (previousNpv == 0.0) brackets.add(previousRate to previousRate)
    if (brackets.isEmpty()) return null
    var (low, high) = brackets.minBy { kotlin.math.abs((it.first + it.second) / 2.0 - 0.10) }
    if (low == high) return low * 100.0
    var lowNpv = npv(low)
    repeat(180) {
        val mid = (low + high) / 2.0
        val midNpv = npv(mid)
        if ((lowNpv < 0.0) == (midNpv < 0.0)) { low = mid; lowNpv = midNpv } else high = mid
    }
    return ((low + high) / 2.0) * 100.0
}

private fun loadBonds(context: android.content.Context): List<Bond> = try {
    val array = JSONArray(context.getSharedPreferences(PREFS, 0).getString(BONDS_KEY, "[]"))
    (0 until array.length()).mapNotNull { index ->
        val item = array.getJSONObject(index)
        val id = item.getString("id")
        val faceValue = item.getDouble("face")
        val cleanPrice = item.getDouble("price")
        val lots = item.optJSONArray("lots")?.let { arrayOfLots ->
            (0 until arrayOfLots.length()).map { lotIndex ->
                val lot = arrayOfLots.getJSONObject(lotIndex)
                val accrued = lot.optDouble("accrued", 0.0)
                val fees = lot.optDouble("fees", 0.0)
                PurchaseLot(
                    id = lot.getString("id"), purchaseDate = LocalDate.parse(lot.getString("purchaseDate"), StorageDateFormat),
                    faceValue = lot.getDouble("face"), cleanPrice = lot.getDouble("price") + accrued + fees,
                    purchaseDateConfirmed = lot.optBoolean("purchaseDateConfirmed", true)
                )
            }
        } ?: listOf(PurchaseLot(id = "legacy-$id", purchaseDate = LocalDate.now(), faceValue = faceValue, cleanPrice = cleanPrice, purchaseDateConfirmed = false))
        Bond(
            id = id, name = item.getString("name"), faceValue = faceValue,
            couponRate = item.getDouble("coupon"), cleanPrice = cleanPrice,
            maturityDate = LocalDate.parse(item.getString("maturity"), StorageDateFormat), nextCouponDate = LocalDate.parse(item.getString("nextCoupon"), StorageDateFormat),
            frequency = item.getInt("frequency"), taxRate = item.getDouble("tax"), lots = lots
        )
    }
} catch (_: Exception) { emptyList() }

private fun persistBonds(context: android.content.Context, bonds: List<Bond>) {
    val array = JSONArray()
    bonds.forEach { bond ->
        array.put(JSONObject().apply {
            put("id", bond.id); put("name", bond.name); put("face", bond.totalFaceValue); put("coupon", bond.couponRate)
            put("price", bond.totalCleanPrice); put("maturity", bond.maturityDate.format(StorageDateFormat)); put("nextCoupon", bond.nextCouponDate.format(StorageDateFormat))
            put("frequency", bond.frequency); put("tax", bond.taxRate)
            put("lots", JSONArray().apply { bond.purchaseLots.forEach { lot -> put(JSONObject().apply {
                put("id", lot.id); put("purchaseDate", lot.purchaseDate.format(StorageDateFormat)); put("purchaseDateConfirmed", lot.purchaseDateConfirmed); put("face", lot.faceValue)
                put("price", lot.cleanPrice)
            }) } })
        })
    }
    context.getSharedPreferences(PREFS, 0).edit().putString(BONDS_KEY, array.toString()).apply()
}

private fun Double.fmt() = if (isFinite()) String.format(Locale.US, "%.2f", this) else "—"
private fun netReturnLabel(bond: Bond) = if (bond.purchaseLots.size > 1) "Estimated Net Return" else "Estimated Net YTM"
private fun money(value: Double) = "UGX " + String.format(Locale.US, "%,.0f", value)
private fun premiumDiscount(bond: Bond): PricePosition {
    val difference = bond.totalCleanPrice - bond.totalFaceValue
    if (kotlin.math.abs(difference) < 0.005) return PricePosition("At Face Value", "UGX 0", Muted)
    val label = if (difference > 0) "Premium" else "Discount"
    val color = if (difference > 0) PremiumRed else DiscountGreen
    return PricePosition(label, money(kotlin.math.abs(difference)), color)
}

private fun String.toAmountOrNull(): Double? = replace(",", "").trim().toDoubleOrNull()

private fun Double.grouped(): String = BigDecimal.valueOf(this).stripTrailingZeros().toPlainString().groupedInput()

private fun String.groupedInput(): String {
    val cleaned = filter { it.isDigit() || it == '.' }
    val decimalAt = cleaned.indexOf('.')
    val integer = if (decimalAt >= 0) cleaned.substring(0, decimalAt) else cleaned
    val fraction = if (decimalAt >= 0) cleaned.substring(decimalAt + 1).replace(".", "") else null
    val groupedInteger = integer.reversed().chunked(3).joinToString(",").reversed()
    return if (fraction != null) "$groupedInteger.$fraction" else groupedInteger
}








