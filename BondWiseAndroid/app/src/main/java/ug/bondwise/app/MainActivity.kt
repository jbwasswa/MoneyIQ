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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.pow

private val Navy = Color(0xFF17365D)
private val Teal = Color(0xFF0F8B8D)
private val Canvas = Color(0xFFF3F6FA)
private val Ink = Color(0xFF172B4D)
private val Muted = Color(0xFF6B7B90)
private val Line = Color(0xFFE0E7EF)
private val DateFormat = DateTimeFormatter.ISO_LOCAL_DATE
private const val PREFS = "bondwise_local"
private const val BONDS_KEY = "saved_bonds"

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
    var page by remember { mutableStateOf("Overview") }
    var rank by remember { mutableStateOf("Net YTM") }
    var showAdd by remember { mutableStateOf(false) }
    var editingBond by remember { mutableStateOf<Bond?>(null) }
    var scheduledBond by remember { mutableStateOf<Bond?>(null) }

    fun save() = persistBonds(context, bonds)
    fun addBond(bond: Bond) { bonds.add(bond); save() }
    fun removeBond(bond: Bond) { bonds.removeAll { it.id == bond.id }; save() }
    fun updateBond(bond: Bond) { val index = bonds.indexOfFirst { it.id == bond.id }; if (index >= 0) { bonds[index] = bond; save() } }

    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, secondary = Navy, background = Canvas, surface = Color.White, onSurface = Ink)) {
        Scaffold(
            containerColor = Canvas,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    listOf("Overview", "Compare", "Calculator", "My bonds").forEach { item ->
                        NavigationBarItem(
                            selected = page == item,
                            onClick = { page = item },
                            icon = { Text(when (item) { "Overview" -> "⌂"; "Compare" -> "⇄"; "Calculator" -> "▦"; else -> "▤" }, fontSize = 18.sp) },
                            label = { Text(item) }
                        )
                    }
                }
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                Header()
                when (page) {
                    "Overview" -> Overview(bonds, onCompare = { page = "Compare" }, onAdd = { showAdd = true }, onSchedule = { scheduledBond = it })
                    "Compare" -> Compare(bonds, rank, { rank = it }, { showAdd = true }, { scheduledBond = it }, { editingBond = it }, ::removeBond)
                    "Calculator" -> Calculator(onSave = ::addBond, onSchedule = { scheduledBond = it })
                    else -> Portfolio(bonds, { scheduledBond = it }, { editingBond = it }, ::removeBond, { showAdd = true })
                }
            }
        }
        if (showAdd) AddBondDialog(onDismiss = { showAdd = false }, onAdd = { addBond(it); showAdd = false })
        editingBond?.let { bond -> EditBondDialog(bond, onDismiss = { editingBond = null }, onSave = { updateBond(it); editingBond = null }) }
        scheduledBond?.let { ScheduleDialog(it, onDismiss = { scheduledBond = null }) }
    }
}

@Composable private fun Header() {
    Column(Modifier.fillMaxWidth().background(Navy, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text("▥  BondWise  UG", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Text("BOND INVESTMENT ASSISTANT", color = Color(0xFFBCD9E5), fontSize = 10.sp, letterSpacing = 1.5.sp)
        Text("Make every bond\ndecision count.", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
        Text("Compare yields, after-tax income and coupon dates.", color = Color(0xFFD5E4EF), fontSize = 13.sp)
    }
}

@Composable private fun Overview(bonds: List<Bond>, onCompare: () -> Unit, onAdd: () -> Unit, onSchedule: (Bond) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Your watchlist", "${bonds.size} BONDS") }
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
            item { CardBox { Text("Highest estimated net YTM", color = Muted, fontSize = 12.sp); Text(best?.let { "${estimatedYtm(it, true)?.fmt() ?: "—"}%" } ?: "—", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold); Text(best?.name ?: "No bond selected", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(5.dp)); Text("Calculated from the dates and tax rate you entered. Not a live market quote.", color = Muted, fontSize = 11.sp) } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetricCard("Bonds saved", bonds.size.toString(), Modifier.weight(1f)); MetricCard("Annual net coupons", money(bonds.sumOf { it.annualNetCoupon }), Modifier.weight(1f)) } }
            item { SectionTitle("Highest estimated net YTM", "YOUR DATA") }
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
    val sorted = when (rank) {
        "Coupon income" -> bonds.sortedByDescending { it.annualNetCoupon }
        "Maturity" -> bonds.sortedBy { it.maturityDate }
        else -> bonds.sortedByDescending { estimatedYtm(it, true) ?: Double.NEGATIVE_INFINITY }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("Compare bonds", "${bonds.size} SAVED") }
        item {
            CardBox {
                Text("Sort by", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Net YTM", "Coupon income", "Maturity").forEach { FilterChip(selected = rank == it, onClick = { onRank(it) }, label = { Text(it, fontSize = 11.sp) }) }
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
        item { TaxNotice() }
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
                        MetricCard("Gross estimated YTM", "${estimatedYtm(bond, false)?.fmt() ?: "—"}%", Modifier.weight(1f))
                        MetricCard("Net estimated YTM", "${estimatedYtm(bond, true)?.fmt() ?: "—"}%", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Dated cash-flow estimate; tax is applied to coupon interest only. Price is treated as clean (excluding accrued interest).", fontSize = 11.sp, color = Muted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { onSchedule(bond) }, modifier = Modifier.fillMaxWidth()) { Text("Preview coupon schedule") }
                    Button(onClick = { onSave(bond) }, modifier = Modifier.fillMaxWidth()) { Text("Save to My bonds") }
                }
            }
        }
        item { TaxNotice() }
    }
}

@Composable private fun Portfolio(bonds: List<Bond>, onSchedule: (Bond) -> Unit, onEdit: (Bond) -> Unit, onRemove: (Bond) -> Unit, onAdd: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("My bonds", "SAVED ON DEVICE") }
        item { CardBox { Text("${bonds.size} bonds saved", fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Your entries stay on this device.", color = Muted, fontSize = 12.sp); Text("Total annual coupon after tax: ${money(bonds.sumOf { it.annualNetCoupon })}", color = Muted, fontSize = 12.sp) } }
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
        TextFieldBlock("Face value (UGX)", form.face, { onChange(form.copy(face = it)) }, KeyboardType.Decimal, groupThousands = true)
        TextFieldBlock("Annual coupon rate (%)", form.coupon, { onChange(form.copy(coupon = it)) }, KeyboardType.Decimal)
        TextFieldBlock("Purchase price, excluding accrued interest (UGX)", form.price, { onChange(form.copy(price = it)) }, KeyboardType.Decimal, groupThousands = true)
        Text("Coupon frequency", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(2 to "Semi-annual", 1 to "Annual").forEach { (n, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = form.frequency == n, onClick = { onChange(form.copy(frequency = n)) }); Text(label, fontSize = 12.sp) }
            }
        }
        DateField("Next coupon date", form.nextCouponDate) { onChange(form.copy(nextCouponDate = it)) }
        DateField("Maturity date", form.maturityDate) { onChange(form.copy(maturityDate = it)) }
        TextFieldBlock("Coupon withholding tax (%)", form.tax, { onChange(form.copy(tax = it)) }, KeyboardType.Decimal, supporting = "Defaults to 10%; change it to the rate applicable to you.")
        Text("BondWise applies this rate to coupon interest only, not principal redemption.", color = Muted, fontSize = 10.sp)
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
            Column { Text(label, fontSize = 10.sp, color = Muted); Text(date.format(DateFormat), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink) }
            Text("Choose date", fontSize = 11.sp, color = Teal, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun TextFieldBlock(label: String, value: String, onValue: (String) -> Unit, keyboard: KeyboardType, supporting: String? = null, groupThousands: Boolean = false) {
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
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)
        )
    } else {
        OutlinedTextField(
            value = value, onValueChange = onValue, label = labelContent, singleLine = true,
            supportingText = supportingContent,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)
        )
    }
}

@Composable private fun BondCard(bond: Bond, isTop: Boolean = false, onSchedule: () -> Unit, onEdit: (() -> Unit)? = null, onRemove: (() -> Unit)?) {
    CardBox {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(bond.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (isTop) Text("TOP BY SELECTED MEASURE", color = Teal, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text("Coupon ${bond.couponRate.fmt()}% · Matures ${bond.maturityDate.format(DateFormat)}", color = Muted, fontSize = 11.sp)
                Text("Next coupon ${bond.nextCouponDate.format(DateFormat)}", color = Muted, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${estimatedYtm(bond, true)?.fmt() ?: "—"}%", color = Teal, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("estimated net YTM", fontSize = 10.sp, color = Muted)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Market price", moneyShort(bond.cleanPrice), Modifier.weight(1f))
            MetricCard("Net annual coupon", moneyShort(bond.annualNetCoupon), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 7.dp)) {
            MetricCard("Gross YTM", "${estimatedYtm(bond, false)?.fmt() ?: "—"}%", Modifier.weight(1f))
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
                Text("Coupon dates from ${bond.nextCouponDate.format(DateFormat)} through maturity. Tax is calculated on coupon interest; principal is returned at maturity.", fontSize = 11.sp, color = Muted)
                schedule.forEach { row ->
                    CardBox {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(row.date.format(DateFormat), fontWeight = FontWeight.Bold, fontSize = 12.sp)
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

@Composable private fun TaxNotice() {
    Notice("Tax rates vary by bond, maturity and investor. The 10% default is editable; confirm the applicable rate before relying on after-tax figures.")
}

@Composable private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Line), shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), content = content)
    }
}

@Composable private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = Color(0xFFF5F8FB), shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Column(Modifier.padding(10.dp)) { Text(label, color = Muted, fontSize = 10.sp); Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
}

@Composable private fun SectionTitle(title: String, tag: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Surface(color = Color(0xFFE1F4F0), shape = RoundedCornerShape(30.dp)) { Text(tag, color = Teal, fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) }
    }
}

@Composable private fun Notice(text: String) {
    Surface(color = Color(0xFFFFF7DF), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFFF0DFAA))) {
        Text(text, color = Color(0xFF70551B), fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(12.dp))
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
            maturityDate = LocalDate.parse(item.getString("maturity")), nextCouponDate = LocalDate.parse(item.getString("nextCoupon")),
            frequency = item.getInt("frequency"), taxRate = item.getDouble("tax")
        )
    }
} catch (_: Exception) { emptyList() }

private fun persistBonds(context: android.content.Context, bonds: List<Bond>) {
    val array = JSONArray()
    bonds.forEach { bond ->
        array.put(JSONObject().apply {
            put("id", bond.id); put("name", bond.name); put("face", bond.faceValue); put("coupon", bond.couponRate)
            put("price", bond.cleanPrice); put("maturity", bond.maturityDate.format(DateFormat)); put("nextCoupon", bond.nextCouponDate.format(DateFormat))
            put("frequency", bond.frequency); put("tax", bond.taxRate)
        })
    }
    context.getSharedPreferences(PREFS, 0).edit().putString(BONDS_KEY, array.toString()).apply()
}

private fun Double.fmt() = if (isFinite()) String.format(Locale.US, "%.2f", this) else "—"
private fun money(value: Double) = "UGX " + String.format(Locale.US, "%,.0f", value)
private fun moneyShort(value: Double) = money(value)

private fun String.toAmountOrNull(): Double? = replace(",", "").trim().toDoubleOrNull()

private fun Double.grouped(): String = toString().groupedInput()

private fun String.groupedInput(): String {
    val cleaned = filter { it.isDigit() || it == '.' }
    val decimalAt = cleaned.indexOf('.')
    val integer = if (decimalAt >= 0) cleaned.substring(0, decimalAt) else cleaned
    val fraction = if (decimalAt >= 0) cleaned.substring(decimalAt + 1).replace(".", "") else null
    val groupedInteger = integer.reversed().chunked(3).joinToString(",").reversed()
    return if (fraction != null) "$groupedInteger.$fraction" else groupedInteger
}








