package com.millmanager.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Typeface
import android.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var db: Db
    private lateinit var root: LinearLayout
    private val green = Color.rgb(22, 91, 70)
    private val pale = Color.rgb(239, 246, 242)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        db = Db(this)
        BackupWorker.schedule(this)
        showHome()
    }

    private fun base(title: String) {
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(18,18,18,18); setBackgroundColor(Color.rgb(248,250,249)); layoutDirection=View.LAYOUT_DIRECTION_RTL }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        val heading = TextView(this).apply { text=title; textSize=24f; setTextColor(green); typeface=Typeface.DEFAULT_BOLD; gravity=Gravity.RIGHT; setPadding(0,8,0,16) }
        root.addView(heading)
    }

    private fun showHome() {
        base("مطاحن تهامة")
        text("نظام متابعة القمح والإنتاج والديزل", 14, Color.DKGRAY)
        card("ملخص المخزون") { db.stockSummary().forEach { text(it,15,Color.DKGRAY) } }
        action("تسجيل وردية جديدة") { showShift() }
        action("تسجيل رصيد افتتاحي") { showOpening() }
        action("توريد ديزل للخزان") { showFuelPurchase() }
        action("تسليم دقيق أو قشرة") { showDispatch() }
        action("أرصدة المخزون") { showStock() }
        action("سجل الورديات") { showShifts() }
        action("سجل التعديلات والحركات") { showAudit() }
        action("إنشاء نسخة احتياطية الآن") { BackupWorker.runNow(this); toast("تم طلب إنشاء النسخة الاحتياطية") }
        action("استعادة نسخة احتياطية") { restoreBackup() }
        action("تصحيح بيانات وردية") { showShiftCorrection() }
        text("النسخ التلقائي: يومياً – آخر 30 نسخة",12,Color.GRAY)
    }

    private fun showShift() {
        base("تسجيل وردية بعد انتهائها")
        val date=field("تاريخ الوردية (YYYY-MM-DD)", SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date()))
        val line=spinner("خط الإنتاج", listOf("الخط الصيني","الخط التركي"))
        val owner=spinner("صاحب القمح", listOf("العامري","علان"))
        val russian=number("عدد أكياس القمح الروسي – 50 كجم")
        val australian=number("عدد أكياس القمح الأسترالي – 50 كجم")
        val flour50=number("إنتاج الدقيق – أكياس 50 كجم")
        val flour25=number("إنتاج الدقيق – أكياس 25 كجم")
        val bran=number("إنتاج القشرة – أكياس 35 كجم")
        val diesel=decimal("الديزل المصروف لهذه الوردية (لتر)")
        val note=field("ملاحظات (اختياري)","")
        action("حفظ الوردية وتحديث الأرصدة") {
            try {
                val id=db.saveShift(date.text.toString().trim(),line.selectedItem.toString(),owner.selectedItem.toString(),
                    russian.text.toString().toIntOrNull()?:0,australian.text.toString().toIntOrNull()?:0,
                    flour50.text.toString().toIntOrNull()?:0,flour25.text.toString().toIntOrNull()?:0,
                    bran.text.toString().toIntOrNull()?:0,diesel.text.toString().toDoubleOrNull()?:0.0,note.text.toString())
                toast("تم حفظ الوردية رقم $id وتحديث الأرصدة")
                showHome()
            } catch(e: Exception) { toast(e.message ?: "تعذر حفظ الوردية") }
        }
        action("رجوع") { showHome() }
    }

    private fun showOpening() {
        base("إدخال الأرصدة الافتتاحية")
        val category=spinner("نوع المخزون",listOf("قمح","دقيق","قشرة"))
        val owner=spinner("المالك",listOf("العامري","علان"))
        val item=spinner("الصنف",listOf("روسي","أسترالي","50 كجم","25 كجم","35 كجم"))
        val qty=decimal("الرصيد الافتتاحي بالأكياس")
        val fuel=decimal("رصيد الديزل الافتتاحي باللتر (اختياري)")
        val cost=decimal("تكلفة لتر الديزل الافتتاحي بالريال (اختياري)")
        action("حفظ الرصيد الافتتاحي") {
            try {
                val q=qty.text.toString().toDoubleOrNull()?:0.0
                require(q>=0) { "الكمية غير صحيحة" }
                db.addOpeningStock(category.selectedItem.toString(),owner.selectedItem.toString(),item.selectedItem.toString(),q)
                val fl=fuel.text.toString().toDoubleOrNull()?:0.0
                val fc=cost.text.toString().toDoubleOrNull()?:0.0
                if(fl>0) db.addFuelOpening(fl,fc)
                toast("تم حفظ الرصيد الافتتاحي")
                showHome()
            } catch(e:Exception) { toast(e.message?:"تعذر الحفظ") }
        }
        action("رجوع") { showHome() }
    }

    private fun showFuelPurchase() {
        base("توريد ديزل")
        val liters=decimal("كمية الديزل المشتراة باللتر")
        val total=decimal("إجمالي قيمة الشراء بالريال")
        action("حفظ التوريد وحساب المتوسط المرجح") {
            try { db.addFuelPurchaseUi(liters.text.toString().toDoubleOrNull()?:0.0,total.text.toString().toDoubleOrNull()?:0.0); toast("تم تسجيل التوريد"); showHome() }
            catch(e:Exception){toast(e.message?:"تعذر الحفظ")}
        }
        action("رجوع") { showHome() }
    }

    private fun showDispatch() {
        base("تسليم الدقيق أو القشرة")
        val owner=spinner("صاحب المخزون",listOf("العامري","علان"))
        val category=spinner("نوع المنتج",listOf("دقيق","قشرة"))
        val item=spinner("وزن الكيس",listOf("50 كجم","25 كجم","35 كجم"))
        val qty=number("عدد الأكياس المصروفة")
        val receiver=field("اسم المستلم","")
        val note=field("ملاحظات","")
        action("تسجيل التسليم وخصم الرصيد") {
            try { db.dispatch(owner.selectedItem.toString(),category.selectedItem.toString(),item.selectedItem.toString(),qty.text.toString().toIntOrNull()?:0,receiver.text.toString(),note.text.toString()); toast("تم تسجيل التسليم"); showHome() }
            catch(e:Exception){toast(e.message?:"تعذر تسجيل التسليم")}
        }
        action("رجوع") { showHome() }
    }

    private fun showShiftCorrection() {
        base("تصحيح بيانات وردية")
        text("أدخل رقم الوردية كما يظهر في سجل الورديات. سيحتفظ التطبيق بالحركة الأصلية ويسجل قيوداً عكسية وتصحيحية.",13,Color.DKGRAY)
        val idField=number("رقم الوردية")
        action("عرض بيانات الوردية") {
            val id=idField.text.toString().toLongOrNull()
            if(id==null) { toast("أدخل رقم وردية صحيحاً"); return@action }
            val data=db.shiftData(id)
            if(data==null) { toast("الوردية غير موجودة"); return@action }
            showShiftCorrectionForm(data)
        }
        action("رجوع") { showHome() }
    }

    private fun showShiftCorrectionForm(d: ShiftData) {
        base("مراجعة وتصحيح الوردية رقم ${d.id}")
        val date=field("التاريخ",d.date)
        val line=spinner("خط الإنتاج",listOf("الخط الصيني","الخط التركي")).apply { setSelection(if(d.line=="الخط التركي") 1 else 0) }
        val owner=spinner("صاحب القمح",listOf("العامري","علان")).apply { setSelection(if(d.owner=="علان") 1 else 0) }
        val russian=number("أكياس القمح الروسي").apply { setText(d.russian.toString()) }
        val australian=number("أكياس القمح الأسترالي").apply { setText(d.australian.toString()) }
        val flour50=number("أكياس الدقيق 50 كجم").apply { setText(d.flour50.toString()) }
        val flour25=number("أكياس الدقيق 25 كجم").apply { setText(d.flour25.toString()) }
        val bran=number("أكياس القشرة 35 كجم").apply { setText(d.bran.toString()) }
        val diesel=decimal("الديزل المصروف باللتر").apply { setText(d.diesel.toString()) }
        val note=field("سبب التصحيح (إلزامي)","")
        action("اعتماد التصحيح مع حفظ السجل السابق") {
            try {
                require(note.text.toString().trim().isNotEmpty()) { "اكتب سبب التصحيح" }
                db.correctShift(d.id,date.text.toString(),line.selectedItem.toString(),owner.selectedItem.toString(),
                    russian.text.toString().toIntOrNull()?:0,australian.text.toString().toIntOrNull()?:0,
                    flour50.text.toString().toIntOrNull()?:0,flour25.text.toString().toIntOrNull()?:0,
                    bran.text.toString().toIntOrNull()?:0,diesel.text.toString().toDoubleOrNull()?:0.0,note.text.toString())
                toast("تم تسجيل التصحيح مع الاحتفاظ بالحركات السابقة")
                showHome()
            } catch(e:Exception) { toast(e.message ?: "تعذر التصحيح") }
        }
        action("إلغاء") { showHome() }
    }

    private fun restoreBackup() {
        val intent=android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE); type="*/*"
        }
        startActivityForResult(intent, 701)
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==701 && resultCode==RESULT_OK && data?.data!=null) {
            try {
                val temp=java.io.File(cacheDir,"restore_mill_manager.db")
                contentResolver.openInputStream(data.data!!)?.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                val check=android.database.sqlite.SQLiteDatabase.openDatabase(temp.absolutePath,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                val valid=check.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='shifts'",null).use { it.moveToFirst() }
                check.close(); require(valid) { "الملف المحدد ليس نسخة احتياطية صالحة للمطحنة" }
                db.close()
                val target=getDatabasePath("mill_manager.db")
                temp.copyTo(target,true); temp.delete()
                toast("تمت استعادة النسخة الاحتياطية. أعد فتح التطبيق.")
                showHome()
            } catch(e:Exception) { toast("فشلت الاستعادة: ${e.message}") }
        }
    }

    private fun showStock() { base("أرصدة المخزون الحالية"); db.stockSummary().forEach { card(it) {} }; action("رجوع") { showHome() } }
    private fun showShifts() { base("آخر الورديات المسجلة"); val rows=db.recentShifts(); if(rows.isEmpty()) text("لا توجد ورديات مسجلة بعد",15,Color.GRAY) else rows.forEach { card(it) {} }; action("رجوع") { showHome() } }
    private fun showAudit() { base("سجل الحركات والتعديلات"); val rows=db.auditRows(); if(rows.isEmpty()) text("لا توجد حركات مسجلة بعد",15,Color.GRAY) else rows.forEach { card(it) {} }; action("رجوع") { showHome() } }

    private fun field(label:String, hint:String): EditText { text(label,14,Color.DKGRAY); return EditText(this).also { it.hint=hint; it.textSize=16f; it.setSingleLine(true); it.layoutDirection=View.LAYOUT_DIRECTION_LTR; root.addView(it,LinearLayout.LayoutParams(-1,-2)) } }
    private fun number(label:String)=field(label,"0").apply { inputType=android.text.InputType.TYPE_CLASS_NUMBER }
    private fun decimal(label:String)=field(label,"0.00").apply { inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
    private fun spinner(label:String, values:List<String>): Spinner { text(label,14,Color.DKGRAY); return Spinner(this).also { it.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,values); root.addView(it) } }
    private fun text(value:String,size:Int,color:Int) { root.addView(TextView(this).apply { text=value; textSize=size.toFloat(); setTextColor(color); gravity=Gravity.RIGHT; setPadding(0,6,0,6) }) }
    private fun card(value:String, content:()->Unit) { val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(14,10,14,10); setBackgroundColor(pale); layoutParams=LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,5,0,5) } }; root.addView(box); val old=root; root=box; text(value,15,Color.DKGRAY); content(); root=old }
    private fun action(label:String, click:()->Unit) { val b=Button(this).apply { text=label; isAllCaps=false; setTextColor(Color.WHITE); setBackgroundColor(green); setOnClickListener { click() } }; root.addView(b,LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,6,0,6) }) }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}
