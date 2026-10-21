package ug.bondwise.app

import android.app.DatePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
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

data class Bond(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val faceValue: Double,
    val couponRate: Double,
    val cleanPrice: Double,
    val maturityDate: LocalDate,
    val nextCouponDate: LocalDate,
    val frequency: Int,
    val taxRate: Double
) {
    val annualGrossCoupon: Double get() = faceValue * couponRate / 100.0
    val annualNetCoupon: Double get() = annualGrossCoupon * (1.0 - taxRate / 100.0)
    val grossCurrentYield: Double get() = if (cleanPrice > 0) annualGrossCoupon / cleanPrice * 100.0 else 0.0
    val netCurrentYield: Double get() = if (cleanPrice > 0) annualNetCoupon / cleanPrice * 100.0 else 0.0
}

private fun Bond.toForm() = BondForm(id = id, name = name, face = faceValue.grouped(), coupon = couponRate.toString(), price = cleanPrice.grouped(), tax = taxRate.toString(), frequency = frequency, nextCouponDate = nextCouponDate, maturityDate = maturityDate)

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
    val nextCouponDate: LocalDate = LocalDate.now().plusMonths(6),
    val maturityDate: LocalDate = LocalDate.now().plusYears(5)
) {
    fun toBond(): Bond? {
        val faceValue = face.toAmountOrNull() ?: return null
        val couponRate = coupon.toDoubleOrNull() ?: return null
        val cleanPrice = price.toAmountOrNull() ?: return null
        val taxRate = tax.toDoubleOrNull() ?: return null
        if (name.isBlank() || faceValue <= 0 || couponRate < 0 || cleanPrice <= 0 || taxRate !in 0.0..100.0) return null
        if (frequency !in listOf(1, 2) || nextCouponDate.isBefore(LocalDate.now()) || !nextCouponDate.isBefore(maturityDate)) return null
        return Bond(
            id = id ?: UUID.randomUUID().toString(), name = name.trim(), faceValue = faceValue, couponRate = couponRate, cleanPrice = cleanPrice,
            maturityDate = maturityDate, nextCouponDate = nextCouponDate, frequency = frequency, taxRate = taxRate
        )
    }
}

@Composable
fun BondWiseApp() {
    val context = LocalContext.current
    val bonds = remember { mutableStateListOf<Bond>().apply { addAll(loadBonds(context)) } }
    val pages = listOf("Overview", "Compare", "Calculator", "My bonds")
    var page by remember { mutableStateOf("Overview") }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    var rank by remember { mutableStateOf("Estimated Net YTM") }
    var showAdd by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var editingBond by remember { mutableStateOf<Bond?>(null) }
    var scheduledBond by remember { mutableStateOf<Bond?>(null) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settledPage ->
            page = pages[settledPage]
        }
    }

    fun save() = persistBonds(context, bonds)
    fun addBond(bond: Bond) { bonds.add(bond); save() }
    fun removeBond(bond: Bond) { bonds.removeAll { it.id == bond.id }; save() }
    fun updateBond(bond: Bond) { val index = bonds.indexOfFirst { it.id == bond.id }; if (index >= 0) { bonds[index] = bond; save() } }

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
                                if (target >= 0) scope.launch { pagerState.scrollToPage(target) }
                            },
                            icon = { Text(when (item) { "Overview" -> "⌂"; "Compare" -> "⇄"; "Calculator" -> "▦"; else -> "▤" }, fontSize = 18.sp) },
                            label = { Text(item) }
                        )
                    }
                }
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                Header(onHelp = { showHelp = true })
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { pageIndex ->
                    when (pages[pageIndex]) {
                        "Overview" -> Overview(bonds, onCompare = { scope.launch { pagerState.scrollToPage(1) } }, onAdd = { showAdd = true }, onSchedule = { scheduledBond = it })
                        "Compare" -> Compare(bonds, rank, { rank = it }, { showAdd = true }, { scheduledBond = it }, { editingBond = it }, ::removeBond)
                        "Calculator" -> Calculator(onSave = ::addBond, onSchedule = { scheduledBond = it })
                        else -> Portfolio(bonds, { scheduledBond = it }, { editingBond = it }, ::removeBond, { showAdd = true })
                    }
                }
            }
        }
        if (showAdd) AddBondDialog(onDismiss = { showAdd = false }, onAdd = { addBond(it); showAdd = false })
        editingBond?.let { bond -> EditBondDialog(bond, onDismiss = { editingBond = null }, onSave = { updateBond(it); editingBond = null }) }
        scheduledBond?.let { ScheduleDialog(it, onDismiss = { scheduledBond = null }) }
        if (showHelp) HelpDialog(onDismiss = { showHelp = false })
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

@Composable private fun Overview(bonds: List<Bond>, onCompare: () -> Unit, onAdd: () -> Unit, onSchedule: (Bond) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Your watchlist", if (bonds.size == 1) "1 BOND" else "${bonds.size} BONDS") }
        if (bonds.isEmpty()) {
            item {
                CardBox {
                    Text("Start with a bond you’re considering", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text("Enter its price, coupon, dates and tax rate. BondWise will calculate estimated yields and future coupon payments.", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Add your first bond") }
                }
            }
        } else {
            val best = bonds.maxByOrNull { estimatedYtm(it, afterTax = true) ?: Double.NEGATIVE_INFINITY }
            item { CardBox { Text("Estimated Net YTM", color = Muted, fontSize = 12.sp); Text(best?.let { "${estimatedYtm(it, true)?.fmt() ?: "—"}%" } ?: "—", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold); Text(best?.name ?: "No bond selected", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(5.dp)); Text("Calculated from the dates and tax rate you entered. Not a live market quote.", color = Muted, fontSize = 11.sp) } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetricCard("Saved bonds", if (bonds.size == 1) "1 Bond Saved" else "${bonds.size} Bonds Saved", Modifier.weight(1f)); MetricCard("Annual net coupons", money(bonds.sumOf { it.annualNetCoupon }), Modifier.weight(1f)) } }
            item { SectionTitle("Estimated Net YTM", "YOUR DATA") }
            items(bonds.sortedByDescending { estimatedYtm(it, true) ?: Double.NEGATIVE_INFINITY }.take(3)) { bond ->
                BondCard(bond, onSchedule = { onSchedule(bond) }, onEdit = null, onRemove = null)
            }
            item { Button(onClick = onCompare, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Compare all bonds  →") } }
            item { OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("+ Add another bond") } }
        }
    }
}

@Composable private fun Compare(
    bonds: List<Bond>, rank: String, onRank: (String) -> Unit, onAdd: () -> Unit,
    onSchedule: (Bond) -> Unit, onEdit: (Bond) -> Unit, onRemove: (Bond) -> Unit
) {
    var compareMode by remember { mutableStateOf("Saved") }
    var firstForm by remember { mutableStateOf(BondForm(name = "Bond A")) }
    var secondForm by remember { mutableStateOf(BondForm(name = "Bond B")) }
    if (compareMode == "Quick compare") {
        QuickCompare(firstForm, { firstForm = it }, secondForm, { secondForm = it }, onSavedBonds = { compareMode = "Saved" })
        return
    }
    val sorted = when (rank) {
        "Coupon income" -> bonds.sortedByDescending { it.annualNetCoupon }
        "Maturity" -> bonds.sortedBy { it.maturityDate }
        else -> bonds.sortedByDescending { estimatedYtm(it, true) ?: Double.NEGATIVE_INFINITY }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionTitle("Compare bonds", "${bonds.size} SAVED")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = true, onClick = { compareMode = "Saved" }, label = { Text("Saved bonds") })
                FilterChip(selected = false, onClick = { compareMode = "Quick compare" }, label = { Text("Quick compare") })
            }
        }
        item {
            CardBox {
                Text("Sort by", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Estimated Net YTM", "Coupon income", "Maturity").forEach { FilterChip(selected = rank == it, onClick = { onRank(it) }, label = { Text(it, fontSize = 9.sp) }) }
                }
                Text("Sorting highlights one measure; it does not decide which bond suits you.", fontSize = 11.sp, color = Muted)
            }
        }
        if (sorted.isEmpty()) {
            item { CardBox { Text("No bonds saved yet", fontWeight = FontWeight.Bold); Text("Add a bond to see its estimated yield and coupon schedule here.", color = Muted, fontSize = 12.sp); Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add a bond") } } }
        } else {
            items(sorted) { bond -> BondCard(bond, isTop = bond == sorted.firstOrNull(), onSchedule = { onSchedule(bond) }, onEdit = { onEdit(bond) }, onRemove = { onRemove(bond) }) }
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
                    CompareResultRow("Face Value", money(firstBond.faceValue), money(secondBond.faceValue))
                    CompareResultRow("Purchase Price", money(firstBond.cleanPrice), money(secondBond.cleanPrice))
                    ComparePricePositionRow(firstBond, secondBond)
                    CompareResultRow("Estimated Net YTM", "${estimatedYtm(firstBond, true)?.fmt() ?: "—"}%", "${estimatedYtm(secondBond, true)?.fmt() ?: "—"}%")
                    CompareResultRow("Estimated Gross YTM", "${estimatedYtm(firstBond, false)?.fmt() ?: "—"}%", "${estimatedYtm(secondBond, false)?.fmt() ?: "—"}%")
                    CompareResultRow("Net annual coupon", money(firstBond.annualNetCoupon), money(secondBond.annualNetCoupon))
                    CompareResultRow("Net current yield", "${firstBond.netCurrentYield.fmt()}%", "${secondBond.netCurrentYield.fmt()}%")
                    CompareResultRow("Maturity", firstBond.maturityDate.format(DisplayDateFormat), secondBond.maturityDate.format(DisplayDateFormat))
                    Spacer(Modifier.height(8.dp))
                    val aYtm = estimatedYtm(firstBond, true)
                    val bYtm = estimatedYtm(secondBond, true)
                    val lead = when {
                        aYtm == null || bYtm == null -> "YTM cannot be estimated for one or both entries with these inputs."
                        aYtm > bYtm -> "${firstBond.name} has the higher estimated net YTM."
                        bYtm > aYtm -> "${secondBond.name} has the higher estimated net YTM."
                        else -> "Both have the same estimated net YTM."
                    }
                    Text(lead, color = Teal, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("This compares entered figures only. It does not include fees, accrued interest, or market convention adjustments.", color = Muted, fontSize = 10.sp)
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
                        MetricCard("Estimated Gross YTM", "${estimatedYtm(bond, false)?.fmt() ?: "—"}%", Modifier.weight(1f))
                        MetricCard("Estimated Net YTM", "${estimatedYtm(bond, true)?.fmt() ?: "—"}%", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Dated cash-flow estimate. Purchase Price is entered without accrued interest.", fontSize = 11.sp, color = Muted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { onSchedule(bond) }, modifier = Modifier.fillMaxWidth()) { Text("Preview coupon schedule") }
                    Button(onClick = { onSave(bond) }, modifier = Modifier.fillMaxWidth()) { Text("Save to My bonds") }
                }
            }
        }
    }
}

@Composable private fun Portfolio(bonds: List<Bond>, onSchedule: (Bond) -> Unit, onEdit: (Bond) -> Unit, onRemove: (Bond) -> Unit, onAdd: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("My bonds", "SAVED ON DEVICE") }
        item { CardBox { Text(if (bonds.size == 1) "1 Bond Saved" else "${bonds.size} Bonds Saved", fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Your entries stay on this device.", color = Muted, fontSize = 12.sp); Text("Total annual coupon after tax: ${money(bonds.sumOf { it.annualNetCoupon })}", color = Muted, fontSize = 12.sp) } }
        if (bonds.isEmpty()) {
            item { CardBox { Text("Your bond list is empty", fontWeight = FontWeight.Bold); Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add a bond") } } }
        } else {
            items(bonds) { bond -> BondCard(bond, onSchedule = { onSchedule(bond) }, onEdit = { onEdit(bond) }, onRemove = { onRemove(bond) }) }
            item { OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("+ Add a bond") } }
        }
    }
}

@Composable private fun BondFields(form: BondForm, onChange: (BondForm) -> Unit) {
    CardBox {
        TextFieldBlock("Bond name", form.name, { onChange(form.copy(name = it)) }, KeyboardType.Text)
        TextFieldBlock("Face Value", form.face, { onChange(form.copy(face = it)) }, KeyboardType.NumberPassword, groupThousands = true)
        TextFieldBlock("Annual coupon rate (%)", form.coupon, { onChange(form.copy(coupon = it)) }, KeyboardType.Decimal)
        TextFieldBlock("Purchase Price", form.price, { onChange(form.copy(price = it)) }, KeyboardType.NumberPassword, groupThousands = true)
        Text("Coupon frequency", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(2 to "Semi-annual", 1 to "Annual").forEach { (n, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = form.frequency == n, onClick = { onChange(form.copy(frequency = n)) }); Text(label, fontSize = 12.sp) }
            }
        }
        DateField("Next coupon date", form.nextCouponDate) { onChange(form.copy(nextCouponDate = it)) }
        DateField("Maturity date", form.maturityDate) { onChange(form.copy(maturityDate = it)) }
        TextFieldBlock("Coupon withholding tax (%)", form.tax, { onChange(form.copy(tax = it)) }, KeyboardType.Decimal)
    }
}

@Composable private fun AddBondDialog(onDismiss: () -> Unit, onAdd: (Bond) -> Unit) {
    var form by remember { mutableStateOf(BondForm()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add bond") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                BondFields(form, { form = it })
                if (error) Text("Check the required values and dates. Next coupon must be today or later and before maturity.", color = Color(0xFF9A5B00), fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { val bond = form.toBond(); if (bond == null) error = true else onAdd(bond) }) { Text("Save bond") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable private fun EditBondDialog(bond: Bond, onDismiss: () -> Unit, onSave: (Bond) -> Unit) {
    var form by remember(bond.id) { mutableStateOf(bond.toForm()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit bond") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            BondFields(form, { form = it })
            if (error) Text("Check the values and dates.", color = Color(0xFF9A5B00), fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = { val updated = form.toBond(); if (updated == null) error = true else onSave(updated) }) { Text("Save changes") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun DateField(label: String, date: LocalDate, onDate: (LocalDate) -> Unit) {
    val context = LocalContext.current
    Surface(color = Color(0xFFF5F8FB), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth().clickable {
        DatePickerDialog(context, { _, year, month, day -> onDate(LocalDate.of(year, month + 1, day)) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(label, fontSize = 10.sp, color = Muted); Text(date.format(DisplayDateFormat), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink) }
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

@Composable private fun BondCard(bond: Bond, isTop: Boolean = false, onSchedule: () -> Unit, onEdit: (() -> Unit)? = null, onRemove: (() -> Unit)?) {
    val position = premiumDiscount(bond)
    CardBox {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(bond.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (isTop) Text("TOP BY SELECTED MEASURE", color = Teal, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text("Coupon ${bond.couponRate.fmt()}% · Matures ${bond.maturityDate.format(DisplayDateFormat)}", color = Muted, fontSize = 11.sp)
                Text("Next coupon ${bond.nextCouponDate.format(DisplayDateFormat)}", color = Muted, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${estimatedYtm(bond, true)?.fmt() ?: "—"}%", color = Teal, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("Estimated Net YTM", fontSize = 10.sp, color = Muted)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Purchase Price", money(bond.cleanPrice), Modifier.weight(1f))
            MetricCard("Face Value", money(bond.faceValue), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 7.dp)) {
            MetricCard("Net annual coupon", money(bond.annualNetCoupon), Modifier.weight(1f))
            MetricCard(position.label, position.detail, Modifier.weight(1f), position.color, position.color)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 7.dp)) {
            MetricCard("Estimated Gross YTM", "${estimatedYtm(bond, false)?.fmt() ?: "—"}%", Modifier.weight(1f))
            MetricCard("Tax on coupons", "${bond.taxRate.fmt()}%", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            TextButton(onClick = onSchedule, modifier = Modifier.weight(1f)) { Text("Schedule") }
            if (onEdit != null) TextButton(onClick = onEdit) { Text("Edit") }
            if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove", color = Color(0xFF9A3D42)) }
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
                            Text(row.date.format(DisplayDateFormat), fontWeight = FontWeight.Bold, fontSize = 12.sp)
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

@Composable private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("BondWise Help") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HelpSection("Tax", "The 10% tax value is an editable default, not a universal rate. Confirm the rate that applies to your bond and circumstances. BondWise applies the entered rate to coupon interest only, not principal repayment.")
                HelpSection("Estimated Net YTM", "This is an annualized estimate based on the entered Purchase Price, coupon dates, maturity, and tax rate. It assumes the bond is held to maturity and payments are made as scheduled. It is not a live market quote or guaranteed return.")
                HelpSection("Purchase Price", "Enter the bond price before accrued interest. The current estimate does not include accrued interest, transaction fees, or market-specific day-count conventions.")
                HelpSection("Premium or discount", "BondWise compares Purchase Price with Face Value. A price above face value is shown as a red premium; a price below face value is shown as a green discount.")
                HelpSection("Compare bonds", "A higher estimated net YTM means a higher modeled return on the figures entered. Also consider maturity, coupon income, liquidity, and whether the bond suits your plans.")
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

@Composable private fun SectionTitle(title: String, tag: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Surface(color = Color(0xFFE1F4F0), shape = RoundedCornerShape(30.dp)) { Text(tag, color = Teal, fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) }
    }
}

private fun couponSchedule(bond: Bond): List<Cashflow> {
    val periodMonths = 12 / bond.frequency
    val result = mutableListOf<Cashflow>()
    val first = bond.nextCouponDate
    val anchorDay = first.dayOfMonth
    var index = 0
    var date = first
    while (!date.isAfter(bond.maturityDate) && index < 1000) {
        val gross = bond.annualGrossCoupon / bond.frequency
        val tax = gross * bond.taxRate / 100.0
        result.add(Cashflow(date, gross, tax, gross - tax, if (date == bond.maturityDate) bond.faceValue else 0.0))
        index++
        val month = YearMonth.from(first).plusMonths((periodMonths.toLong() * index))
        date = month.atDay(anchorDay.coerceAtMost(month.lengthOfMonth()))
    }
    if (result.none { it.date == bond.maturityDate }) {
        result.add(Cashflow(bond.maturityDate, 0.0, 0.0, 0.0, bond.faceValue))
    }
    return result.sortedBy { it.date }
}

private fun estimatedYtm(bond: Bond, afterTax: Boolean): Double? {
    val today = LocalDate.now()
    val flows = couponSchedule(bond).filter { !it.date.isBefore(today) }
    if (flows.isEmpty() || bond.cleanPrice <= 0) return null
    fun presentValue(rate: Double): Double = flows.sumOf { flow ->
        val cash = (if (afterTax) flow.netCoupon else flow.grossCoupon) + flow.principal
        cash / (1.0 + rate).pow(java.time.temporal.ChronoUnit.DAYS.between(today, flow.date) / 365.25)
    }
    var low = -0.99
    var high = 5.0
    if (presentValue(low) < bond.cleanPrice || presentValue(high) > bond.cleanPrice) return null
    repeat(180) {
        val mid = (low + high) / 2.0
        if (presentValue(mid) > bond.cleanPrice) low = mid else high = mid
    }
    return ((low + high) / 2.0) * 100.0
}

private fun loadBonds(context: android.content.Context): List<Bond> = try {
    val array = JSONArray(context.getSharedPreferences(PREFS, 0).getString(BONDS_KEY, "[]"))
    (0 until array.length()).mapNotNull { index ->
        val item = array.getJSONObject(index)
        Bond(
            id = item.getString("id"), name = item.getString("name"), faceValue = item.getDouble("face"),
            couponRate = item.getDouble("coupon"), cleanPrice = item.getDouble("price"),
            maturityDate = LocalDate.parse(item.getString("maturity"), StorageDateFormat), nextCouponDate = LocalDate.parse(item.getString("nextCoupon"), StorageDateFormat),
            frequency = item.getInt("frequency"), taxRate = item.getDouble("tax")
        )
    }
} catch (_: Exception) { emptyList() }

private fun persistBonds(context: android.content.Context, bonds: List<Bond>) {
    val array = JSONArray()
    bonds.forEach { bond ->
        array.put(JSONObject().apply {
            put("id", bond.id); put("name", bond.name); put("face", bond.faceValue); put("coupon", bond.couponRate)
            put("price", bond.cleanPrice); put("maturity", bond.maturityDate.format(StorageDateFormat)); put("nextCoupon", bond.nextCouponDate.format(StorageDateFormat))
            put("frequency", bond.frequency); put("tax", bond.taxRate)
        })
    }
    context.getSharedPreferences(PREFS, 0).edit().putString(BONDS_KEY, array.toString()).apply()
}

private fun Double.fmt() = if (isFinite()) String.format(Locale.US, "%.2f", this) else "—"
private fun money(value: Double) = "UGX " + String.format(Locale.US, "%,.0f", value)
private fun premiumDiscount(bond: Bond): PricePosition {
    val difference = bond.cleanPrice - bond.faceValue
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








