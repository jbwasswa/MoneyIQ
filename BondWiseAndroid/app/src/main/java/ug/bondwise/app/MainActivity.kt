package ug.bondwise.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import kotlin.math.pow

private val Navy = Color(0xFF17365D)
private val Teal = Color(0xFF0F8B8D)
private val Canvas = Color(0xFFF3F6FA)
private val Ink = Color(0xFF172B4D)
private val Muted = Color(0xFF6B7B90)
private val Line = Color(0xFFE0E7EF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { BondWiseApp() } }
}

data class Bond(val id: String = UUID.randomUUID().toString(), val name: String, val face: Double, val coupon: Double, val price: Double, val years: Double, val frequency: Int) {
    val annualCoupon get() = face * coupon / 100.0
    val currentYield get() = if (price > 0) annualCoupon / price * 100.0 else 0.0
    // Periodic IRR solved by bisection; assumes regular periods, redemption at par, and price excluding accrued interest.
    val ytm: Double get() = calculateYtm(face, coupon, price, years, frequency)
}

private fun calculateYtm(face: Double, couponPct: Double, cleanPrice: Double, years: Double, frequency: Int): Double {
    if (face <= 0 || cleanPrice <= 0 || years <= 0 || frequency <= 0) return 0.0
    val n = (years * frequency).toInt().coerceAtLeast(1)
    val c = face * couponPct / 100.0 / frequency
    fun pv(rate: Double): Double = (1..n).sumOf { period -> (c + if (period == n) face else 0.0) / (1.0 + rate).pow(period.toDouble()) }
    var low = -0.99
    var high = 5.0
    repeat(180) { val mid = (low + high) / 2.0; if (pv(mid) > cleanPrice) low = mid else high = mid }
    return (((low + high) / 2.0) * frequency * 100.0)
}

@Composable
fun BondWiseApp() {
    val bonds = remember { mutableStateListOf(
        Bond(name="Treasury Bond A", face=100_000_000.0, coupon=15.8, price=108_000_000.0, years=10.0, frequency=2),
        Bond(name="Treasury Bond B", face=100_000_000.0, coupon=12.5, price=88_500_000.0, years=8.0, frequency=2),
        Bond(name="Treasury Bond C", face=100_000_000.0, coupon=13.0, price=97_000_000.0, years=5.0, frequency=2)
    ) }
    var page by remember { mutableStateOf("Overview") }
    var rank by remember { mutableStateOf("YTM") }
    var showAdd by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = lightColorScheme(primary=Teal, secondary=Navy, background=Canvas, surface=Color.White, onSurface=Ink)) {
        Scaffold(containerColor=Canvas, bottomBar={ NavigationBar(containerColor=Color.White) { listOf("Overview","Compare","Calculator","My bonds").forEach { item -> NavigationBarItem(selected=page==item,onClick={page=item},icon={ Text(when(item){"Overview"->"⌂";"Compare"->"⇄";"Calculator"->"▦";else->"▤"},fontSize=18.sp) },label={Text(item)}) } } }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                Header()
                when(page) {
                    "Overview" -> Overview(bonds, onCompare={page="Compare"})
                    "Compare" -> Compare(bonds, rank, {rank=it}, {showAdd=true})
                    "Calculator" -> Calculator()
                    else -> Portfolio(bonds)
                }
            }
        }
        if(showAdd) AddBondDialog(onDismiss={showAdd=false}, onAdd={bonds.add(it); showAdd=false})
    }
}

@Composable private fun Header() { Column(Modifier.fillMaxWidth().background(Navy, RoundedCornerShape(bottomStart=24.dp,bottomEnd=24.dp)).padding(horizontal=20.dp, vertical=18.dp)) {
    Text("▥  BondWise  UG",color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold)
    Spacer(Modifier.height(18.dp)); Text("BOND INVESTMENT ASSISTANT",color=Color(0xFFBCD9E5),fontSize=10.sp,letterSpacing=1.5.sp)
    Text("Make every bond\ndecision count.",color=Color.White,fontSize=27.sp,fontWeight=FontWeight.Bold,lineHeight=30.sp)
    Text("Compare yields, cash flows and maturity.",color=Color(0xFFD5E4EF),fontSize=13.sp)
} }

@Composable private fun Overview(bonds: List<Bond>, onCompare: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=14.dp), contentPadding=PaddingValues(vertical=14.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Market snapshot", "DEMO DATA") }
        item { CardBox { Text("Highest estimated YTM",color=Muted,fontSize=13.sp); Text("${(bonds.maxOfOrNull{it.ytm}?:0.0).fmt()}%",fontSize=31.sp,fontWeight=FontWeight.ExtraBold,color=Ink); Text("Among bonds in your watchlist",color=Muted,fontSize=12.sp); Spacer(Modifier.height(6.dp)); Text("Prices and yields are examples, not live market quotes.",color=Muted,fontSize=11.sp) } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { MetricCard("Bonds saved",bonds.size.toString(),Modifier.weight(1f)); MetricCard("Top coupon", "${(bonds.maxOfOrNull{it.coupon}?:0.0).fmt()}%",Modifier.weight(1f)) } }
        item { SectionTitle("Bond ideas", "VIEW ALL") }
        items(bonds.sortedByDescending{it.ytm}.take(3)) { BondCard(it) }
        item { Button(onClick=onCompare,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)) { Text("Compare bond deals  →") } }
    }
}

@Composable private fun Compare(bonds: List<Bond>, rank: String, onRank:(String)->Unit, onAdd:()->Unit) {
    val sorted = when(rank) { "Coupon"->bonds.sortedByDescending{it.coupon}; "Maturity"->bonds.sortedBy{it.years}; else->bonds.sortedByDescending{it.ytm} }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=14.dp),contentPadding=PaddingValues(vertical=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("Compare bonds", "${bonds.size} SAVED") }
        item { CardBox { Text("Rank bonds by",fontWeight=FontWeight.Bold); Spacer(Modifier.height(8.dp)); Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("YTM","Coupon","Maturity").forEach { FilterChip(selected=rank==it,onClick={onRank(it)},label={Text(it)}) } }; Text("Ranking highlights one metric. It is not a personal recommendation.",fontSize=11.sp,color=Muted) } }
        items(sorted) { BondCard(it, isTop=it==sorted.firstOrNull()) }
        item { Button(onClick=onAdd,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)) { Text("+ Add a bond") } }
        item { Notice("Highest YTM is not automatically best. Consider maturity, liquidity, taxes, fees and your income needs.") }
    }
}

@Composable private fun Calculator() {
    var name by remember { mutableStateOf("My bond") }; var face by remember { mutableStateOf("100000000") }; var coupon by remember { mutableStateOf("15.8") }; var price by remember { mutableStateOf("95000000") }; var years by remember { mutableStateOf("10") }; var freq by remember { mutableIntStateOf(2) }
    val f=face.toDoubleOrNull()?:0.0; val c=coupon.toDoubleOrNull()?:0.0; val p=price.toDoubleOrNull()?:0.0; val y=years.toDoubleOrNull()?:0.0
    val bond=Bond(name=name,face=f,coupon=c,price=p,years=y,frequency=freq)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=14.dp),contentPadding=PaddingValues(vertical=14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Bond calculator", "ESTIMATE") }
        item { CardBox {
            InputField("Bond name",name,{name=it},KeyboardType.Text)
            InputField("Face value (UGX)",face,{face=it},KeyboardType.Decimal)
            InputField("Annual coupon rate (%)",coupon,{coupon=it},KeyboardType.Decimal)
            InputField("Purchase price, excluding accrued interest (UGX)",price,{price=it},KeyboardType.Decimal)
            InputField("Years to maturity",years,{years=it},KeyboardType.Decimal)
            Text("Coupon frequency",fontWeight=FontWeight.SemiBold,fontSize=13.sp); Row { listOf(2 to "Semi-annual",1 to "Annual").forEach { (n,label)-> Row(verticalAlignment=Alignment.CenterVertically) { RadioButton(selected=freq==n,onClick={freq=n}); Text(label,fontSize=12.sp) } } }
        } }
        item { CardBox { Text("Estimated annual coupon",color=Muted,fontSize=12.sp); Text(money(bond.annualCoupon),fontSize=23.sp,fontWeight=FontWeight.Bold); Spacer(Modifier.height(12.dp)); Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { MetricCard("Current yield","${bond.currentYield.fmt()}%",Modifier.weight(1f)); MetricCard("Estimated YTM","${bond.ytm.fmt()}%",Modifier.weight(1f)) }; Spacer(Modifier.height(8.dp)); Text("YTM uses regular coupon periods, par redemption and price excluding accrued interest. Actual settlement dates and market conventions may change the result.",fontSize=11.sp,color=Muted) } }
        item { Notice("Illustrative calculation only. No live market prices are connected.") }
    }
}

@Composable private fun Portfolio(bonds: List<Bond>) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=14.dp),contentPadding=PaddingValues(vertical=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("My bonds", "ON THIS DEVICE") }
        item { CardBox { Text("Watchlist",color=Muted,fontSize=12.sp); Text("${bonds.size} bonds",fontSize=27.sp,fontWeight=FontWeight.Bold); Text("Portfolio holdings and transaction history are planned for a later version.",color=Muted,fontSize=12.sp) } }
        items(bonds) { BondCard(it) }
    }
}

@Composable private fun BondCard(bond: Bond, isTop: Boolean=false) { CardBox {
    Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) { Text(bond.name,fontWeight=FontWeight.Bold,fontSize=14.sp); if(isTop) Text("TOP BY SELECTED METRIC",color=Teal,fontSize=9.sp,fontWeight=FontWeight.Bold); Text("Coupon ${bond.coupon.fmt()}% · ${bond.years.fmt()} years left",color=Muted,fontSize=11.sp) }
        Column(horizontalAlignment=Alignment.End) { Text("${bond.ytm.fmt()}%",color=Teal,fontSize=22.sp,fontWeight=FontWeight.ExtraBold); Text("estimated YTM",fontSize=10.sp,color=Muted) }
    }
    Spacer(Modifier.height(10.dp)); Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { MetricCard("Price",moneyShort(bond.price),Modifier.weight(1f)); MetricCard("Annual coupon",moneyShort(bond.annualCoupon),Modifier.weight(1f)); MetricCard("Current yield","${bond.currentYield.fmt()}%",Modifier.weight(1f)) }
} }

@Composable private fun AddBondDialog(onDismiss:()->Unit,onAdd:(Bond)->Unit) {
    var name by remember{mutableStateOf("")}; var face by remember{mutableStateOf("100000000")}; var coupon by remember{mutableStateOf("")}; var price by remember{mutableStateOf("")}; var years by remember{mutableStateOf("")}; var freq by remember{mutableIntStateOf(2)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Add bond")},text={Column { InputField("Bond name",name,{name=it},KeyboardType.Text); InputField("Face value (UGX)",face,{face=it},KeyboardType.Decimal); InputField("Annual coupon rate (%)",coupon,{coupon=it},KeyboardType.Decimal); InputField("Market price (UGX)",price,{price=it},KeyboardType.Decimal); InputField("Years to maturity",years,{years=it},KeyboardType.Decimal); Row(verticalAlignment=Alignment.CenterVertically){Text("Semi-annual coupons"); Switch(checked=freq==2,onCheckedChange={freq=if(it)2 else 1})} }},confirmButton={TextButton(onClick={val f=face.toDoubleOrNull()?:0.0; val c=coupon.toDoubleOrNull()?:-1.0; val p=price.toDoubleOrNull()?:0.0; val y=years.toDoubleOrNull()?:0.0; if(name.isNotBlank()&&f>0&&c>=0&&p>0&&y>0) onAdd(Bond(name=name,face=f,coupon=c,price=p,years=y,frequency=freq))}){Text("Add")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable private fun InputField(label:String,value:String,onValue:(String)->Unit,keyboard:KeyboardType) { OutlinedTextField(value=value,onValueChange=onValue,label={Text(label,fontSize=12.sp)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=keyboard),modifier=Modifier.fillMaxWidth().padding(bottom=6.dp)) }
@Composable private fun CardBox(content:@Composable ColumnScope.()->Unit) { Surface(color=Color.White,shape=RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Line),shadowElevation=1.dp,modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(15.dp),content=content) } }
@Composable private fun MetricCard(label:String,value:String,modifier:Modifier=Modifier) { Surface(color=Color(0xFFF5F8FB),shape=RoundedCornerShape(12.dp),modifier=modifier) { Column(Modifier.padding(10.dp)) { Text(label,color=Muted,fontSize=10.sp); Text(value,fontWeight=FontWeight.Bold,fontSize=13.sp) } } }
@Composable private fun SectionTitle(title:String,tag:String) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Text(title,fontSize=18.sp,fontWeight=FontWeight.Bold); Surface(color=Color(0xFFE1F4F0),shape=RoundedCornerShape(30.dp)) { Text(tag,color=Teal,fontWeight=FontWeight.Bold,fontSize=9.sp,modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp)) } } }
@Composable private fun Notice(text:String) { Surface(color=Color(0xFFFFF7DF),shape=RoundedCornerShape(12.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xFFF0DFAA))) { Text(text,color=Color(0xFF70551B),fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.padding(12.dp)) } }
private fun Double.fmt() = if(isFinite()) String.format("%.2f",this) else "—"
private fun money(n:Double) = "UGX " + String.format("%,.0f",n)
private fun moneyShort(n:Double) = "UGX " + String.format("%.1fM",n/1_000_000.0)


