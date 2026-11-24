package com.millmanager.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ShiftData(val id:Long,val date:String,val line:String,val owner:String,val russian:Int,val australian:Int,
                     val flour50:Int,val flour25:Int,val bran:Int,val diesel:Double)

class Db(context: Context) : SQLiteOpenHelper(context, "mill_manager.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE shifts(
            id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, line TEXT NOT NULL,
            owner TEXT NOT NULL, russian_bags INTEGER NOT NULL, australian_bags INTEGER NOT NULL,
            flour50 INTEGER NOT NULL, flour25 INTEGER NOT NULL, bran35 INTEGER NOT NULL,
            diesel_liters REAL NOT NULL, diesel_unit_cost REAL NOT NULL,
            diesel_total_cost REAL NOT NULL, note TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL,
            corrected INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE stock_moves(
            id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, owner TEXT NOT NULL,
            item TEXT NOT NULL, qty REAL NOT NULL, reference_type TEXT NOT NULL,
            reference_id INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL,
            reverses_move_id INTEGER)""")
        db.execSQL("""CREATE TABLE fuel_moves(
            id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, liters REAL NOT NULL,
            unit_cost REAL NOT NULL, total_cost REAL NOT NULL, line TEXT NOT NULL DEFAULT '',
            shift_id INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL,
            reverses_move_id INTEGER)""")
        db.execSQL("""CREATE TABLE audit_log(
            id INTEGER PRIMARY KEY AUTOINCREMENT, entity TEXT NOT NULL, entity_id INTEGER NOT NULL,
            action TEXT NOT NULL, old_data TEXT NOT NULL, new_data TEXT NOT NULL,
            reason TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX idx_stock_balance ON stock_moves(category, owner, item)")
        db.execSQL("CREATE INDEX idx_shift_date ON shifts(date)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { }

    fun addOpening(category: String, owner: String, item: String, qty: Double, note: String = "رصيد افتتاحي") {
        require(qty >= 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = now()
            val v = ContentValues().apply {
                put("category", category); put("owner", owner); put("item", item); put("qty", qty)
                put("reference_type", "opening"); put("reference_id", 0); put("note", note); put("created_at", now)
            }
            db.insertOrThrow("stock_moves", null, v)
            log(db, "رصيد", 0, "إضافة رصيد افتتاحي", "", "$category|$owner|$item|$qty", note)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun addFuelOpening(liters: Double, unitCost: Double) = addFuelMove("افتتاحي", liters, unitCost, "", 0, "رصيد افتتاحي")

    fun addFuelPurchase(liters: Double, totalCost: Double, note: String) {
        require(liters > 0 && totalCost >= 0)
        addFuelMove("توريد", liters, totalCost / liters, "", 0, note)
    }

    private fun addFuelMove(kind: String, liters: Double, unitCost: Double, line: String, shiftId: Long, note: String) {
        require(liters >= 0 && unitCost >= 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val v = ContentValues().apply {
                put("kind", kind); put("liters", if (kind == "صرف") -liters else liters)
                put("unit_cost", unitCost); put("total_cost", (if (kind == "صرف") -1 else 1) * liters * unitCost); put("line", line)
                put("shift_id", shiftId); put("note", note); put("created_at", now())
            }
            db.insertOrThrow("fuel_moves", null, v)
            log(db, "ديزل", 0, kind, "", "$liters لتر × $unitCost", note)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun saveShift(date: String, line: String, owner: String, russian: Int, australian: Int,
                  flour50: Int, flour25: Int, bran35: Int, diesel: Double, note: String): Long {
        require(russian >= 0 && australian >= 0 && flour50 >= 0 && flour25 >= 0 && bran35 >= 0 && diesel >= 0)
        require(russian + australian > 0) { "أدخل كمية القمح" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            require(stockBalance(db, "قمح", owner, "روسي") + 0.0001 >= russian) { "رصيد القمح الروسي غير كافٍ لهذا المالك" }
            require(stockBalance(db, "قمح", owner, "أسترالي") + 0.0001 >= australian) { "رصيد القمح الأسترالي غير كافٍ لهذا المالك" }
            val avg = fuelAverageCost(db)
            val fuelBalance = fuelBalance(db)
            require(diesel <= fuelBalance + 0.0001) { "كمية الديزل المصروفة أكبر من رصيد الخزان" }
            val v = ContentValues().apply {
                put("date", date); put("line", line); put("owner", owner); put("russian_bags", russian)
                put("australian_bags", australian); put("flour50", flour50); put("flour25", flour25)
                put("bran35", bran35); put("diesel_liters", diesel); put("diesel_unit_cost", avg)
                put("diesel_total_cost", diesel * avg); put("note", note); put("created_at", now())
            }
            val id = db.insertOrThrow("shifts", null, v)
            addStockMove(db, "قمح", owner, "روسي", -russian.toDouble(), "وردية", id, "استهلاك قمح")
            addStockMove(db, "قمح", owner, "أسترالي", -australian.toDouble(), "وردية", id, "استهلاك قمح")
            addStockMove(db, "دقيق", owner, "50 كجم", flour50.toDouble(), "وردية", id, "إنتاج")
            addStockMove(db, "دقيق", owner, "25 كجم", flour25.toDouble(), "وردية", id, "إنتاج")
            addStockMove(db, "قشرة", owner, "35 كجم", bran35.toDouble(), "وردية", id, "إنتاج")
            if (diesel > 0) {
                val fv = ContentValues().apply {
                    put("kind", "صرف"); put("liters", -diesel); put("unit_cost", avg)
                    put("total_cost", -diesel * avg); put("line", line); put("shift_id", id)
                    put("note", "صرف ديزل للوردية"); put("created_at", now())
                }
                db.insertOrThrow("fuel_moves", null, fv)
            }
            log(db, "وردية", id, "تسجيل", "", "${date}|$line|$owner|قمح:${russian + australian}|ديزل:$diesel", note)
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    fun shiftData(id:Long): ShiftData? {
        val c=readableDatabase.rawQuery("SELECT id,date,line,owner,russian_bags,australian_bags,flour50,flour25,bran35,diesel_liters FROM shifts WHERE id=?", arrayOf(id.toString()))
        val result=if(c.moveToFirst()) ShiftData(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4),c.getInt(5),c.getInt(6),c.getInt(7),c.getInt(8),c.getDouble(9)) else null
        c.close(); return result
    }

    fun correctShift(id:Long,date:String,line:String,owner:String,russian:Int,australian:Int,flour50:Int,flour25:Int,bran:Int,diesel:Double,reason:String) {
        require(reason.isNotBlank()) { "سبب التصحيح مطلوب" }
        require(russian>=0 && australian>=0 && flour50>=0 && flour25>=0 && bran>=0 && diesel>=0) { "توجد كمية سالبة أو غير صحيحة" }
        require(russian+australian>0) { "يجب إدخال كمية القمح" }
        val db=writableDatabase; db.beginTransaction()
        try {
            val old=shiftData(id) ?: error("الوردية غير موجودة")
            val oldText="${old.date}|${old.line}|${old.owner}|${old.russian}|${old.australian}|${old.flour50}|${old.flour25}|${old.bran}|${old.diesel}"
            // Reverse the net inventory effect of all earlier versions of this shift.
            val c=db.rawQuery("SELECT category,owner,item,SUM(qty) FROM stock_moves WHERE reference_id=? AND reference_type IN ('وردية','تصحيح وردية','عكس تصحيح وردية') GROUP BY category,owner,item",arrayOf(id.toString()))
            val nets=mutableListOf<Array<Any>>()
            while(c.moveToNext()) nets.add(arrayOf(c.getString(0),c.getString(1),c.getString(2),c.getDouble(3)))
            c.close()
            for(n in nets) {
                val q=n[3] as Double
                if(kotlin.math.abs(q)>0.000001) addStockMove(db,n[0] as String,n[1] as String,n[2] as String,-q,"عكس تصحيح وردية",id,"عكس أثر الوردية رقم $id: $reason")
            }
            val fc=db.rawQuery("SELECT COALESCE(SUM(liters),0),COALESCE(SUM(total_cost),0) FROM fuel_moves WHERE shift_id=?",arrayOf(id.toString()))
            fc.moveToFirst(); val oldLiters=fc.getDouble(0); val oldCost=fc.getDouble(1); fc.close()
            if(kotlin.math.abs(oldLiters)>0.000001 || kotlin.math.abs(oldCost)>0.000001) {
                val v=ContentValues().apply { put("kind","عكس تصحيح وردية"); put("liters",-oldLiters); put("unit_cost",if(kotlin.math.abs(oldLiters)>0.000001) oldCost/oldLiters else 0.0); put("total_cost",-oldCost); put("line",old.line); put("shift_id",id); put("note","عكس صرف الوردية: $reason"); put("created_at",now()) }
                db.insertOrThrow("fuel_moves",null,v)
            }
            require(stockBalance(db,"دقيق",old.owner,"50 كجم") >= -0.0001 && stockBalance(db,"دقيق",old.owner,"25 كجم") >= -0.0001 && stockBalance(db,"قشرة",old.owner,"35 كجم") >= -0.0001) { "لا يمكن تصحيح الوردية لأن جزءاً من إنتاجها سُلّم بالفعل. راجع التسليمات أولاً." }
            require(stockBalance(db,"قمح",owner,"روسي")+0.0001>=russian) { "رصيد القمح الروسي غير كافٍ بعد التصحيح" }
            require(stockBalance(db,"قمح",owner,"أسترالي")+0.0001>=australian) { "رصيد القمح الأسترالي غير كافٍ بعد التصحيح" }
            require(diesel<=fuelBalance(db)+0.0001) { "رصيد الديزل غير كافٍ بعد التصحيح" }
            val avg=fuelAverageCost(db)
            val u=ContentValues().apply { put("date",date); put("line",line); put("owner",owner); put("russian_bags",russian); put("australian_bags",australian); put("flour50",flour50); put("flour25",flour25); put("bran35",bran); put("diesel_liters",diesel); put("diesel_unit_cost",avg); put("diesel_total_cost",diesel*avg); put("note",reason); put("corrected",1) }
            db.update("shifts",u,"id=?",arrayOf(id.toString()))
            addStockMove(db,"قمح",owner,"روسي",-russian.toDouble(),"تصحيح وردية",id,"استهلاك روسي بعد التصحيح")
            addStockMove(db,"قمح",owner,"أسترالي",-australian.toDouble(),"تصحيح وردية",id,"استهلاك أسترالي بعد التصحيح")
            addStockMove(db,"دقيق",owner,"50 كجم",flour50.toDouble(),"تصحيح وردية",id,"إنتاج بعد التصحيح")
            addStockMove(db,"دقيق",owner,"25 كجم",flour25.toDouble(),"تصحيح وردية",id,"إنتاج بعد التصحيح")
            addStockMove(db,"قشرة",owner,"35 كجم",bran.toDouble(),"تصحيح وردية",id,"إنتاج بعد التصحيح")
            if(diesel>0) { val v=ContentValues().apply { put("kind","صرف"); put("liters",-diesel); put("unit_cost",avg); put("total_cost",-diesel*avg); put("line",line); put("shift_id",id); put("note","صرف بعد التصحيح"); put("created_at",now()) }; db.insertOrThrow("fuel_moves",null,v) }
            val newText="$date|$line|$owner|$russian|$australian|$flour50|$flour25|$bran|$diesel"
            log(db,"وردية",id,"تصحيح",oldText,newText,reason)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun dispatch(owner: String, category: String, item: String, qty: Int, receiver: String, note: String) {
        require(qty > 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val balance = stockBalance(db, category, owner, item)
            require(qty <= balance + 0.0001) { "الكمية المطلوبة أكبر من الرصيد المتاح: $balance" }
            val id = addStockMove(db, category, owner, item, -qty.toDouble(), "تسليم", 0, "المستلم: $receiver. $note")
            log(db, "مخزون", id, "تسليم", "$category|$owner|$item|$balance", "صرف:$qty، المستلم:$receiver", note)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun correctDispatch(moveId: Long, reason: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val c = db.rawQuery("SELECT category,owner,item,qty FROM stock_moves WHERE id=?", arrayOf(moveId.toString()))
            if (!c.moveToFirst()) { c.close(); error("الحركة غير موجودة") }
            val category=c.getString(0); val owner=c.getString(1); val item=c.getString(2); val qty=c.getDouble(3); c.close()
            addStockMove(db, category, owner, item, -qty, "عكس حركة", moveId, "عكس الحركة رقم $moveId: $reason", moveId)
            log(db, "مخزون", moveId, "عكس حركة", "$qty", "عكس بقيمة ${-qty}", reason)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun addStockMove(db: SQLiteDatabase, category: String, owner: String, item: String, qty: Double,
                             refType: String, refId: Long, note: String, reverses: Long? = null): Long {
        val v = ContentValues().apply {
            put("category", category); put("owner", owner); put("item", item); put("qty", qty)
            put("reference_type", refType); put("reference_id", refId); put("note", note); put("created_at", now())
            if (reverses != null) put("reverses_move_id", reverses)
        }
        return db.insertOrThrow("stock_moves", null, v)
    }

    private fun log(db: SQLiteDatabase, entity: String, id: Long, action: String, old: String, new: String, reason: String) {
        val v = ContentValues().apply {
            put("entity", entity); put("entity_id", id); put("action", action); put("old_data", old)
            put("new_data", new); put("reason", reason); put("created_at", now())
        }
        db.insertOrThrow("audit_log", null, v)
    }

    fun stockBalance(category: String, owner: String, item: String): Double = stockBalance(readableDatabase, category, owner, item)
    private fun stockBalance(db: SQLiteDatabase, category: String, owner: String, item: String): Double {
        val c = db.rawQuery("SELECT COALESCE(SUM(qty),0) FROM stock_moves WHERE category=? AND owner=? AND item=?", arrayOf(category, owner, item))
        c.moveToFirst(); val result=c.getDouble(0); c.close(); return result
    }

    fun fuelBalance(): Double = fuelBalance(readableDatabase)
    private fun fuelBalance(db: SQLiteDatabase): Double {
        val c = db.rawQuery("SELECT COALESCE(SUM(liters),0) FROM fuel_moves", null); c.moveToFirst(); val n=c.getDouble(0); c.close(); return n
    }

    fun fuelAverageCost(): Double = fuelAverageCost(readableDatabase)
    private fun fuelAverageCost(db: SQLiteDatabase): Double {
        val c = db.rawQuery("SELECT COALESCE(SUM(total_cost),0), COALESCE(SUM(liters),0) FROM fuel_moves", null)
        c.moveToFirst(); val value=c.getDouble(0); val qty=c.getDouble(1); c.close()
        return if (qty > 0) value / qty else 0.0
    }

    fun recentShifts(): List<String> {
        val out = mutableListOf<String>()
        val c = readableDatabase.rawQuery("SELECT id,date,line,owner,russian_bags,australian_bags,flour50,flour25,bran35,diesel_liters,diesel_total_cost FROM shifts ORDER BY id DESC LIMIT 30", null)
        while (c.moveToNext()) out.add("#${c.getLong(0)} | ${c.getString(1)} | ${c.getString(2)} | ${c.getString(3)} | قمح ${c.getInt(4)+c.getInt(5)} كيس | ديزل ${fmt(c.getDouble(9))} لتر")
        c.close(); return out
    }

    fun stockSummary(): List<String> {
        val out= mutableListOf<String>()
        for (owner in listOf("العامري","علان")) {
            for (item in listOf("روسي","أسترالي")) out.add("قمح $owner – $item: ${fmt(stockBalance("قمح",owner,item))} كيس")
            for (item in listOf("50 كجم","25 كجم")) out.add("دقيق $owner – $item: ${fmt(stockBalance("دقيق",owner,item))} كيس")
            out.add("قشرة $owner – 35 كجم: ${fmt(stockBalance("قشرة",owner,"35 كجم"))} كيس")
        }
        out.add("ديزل الخزان المشترك: ${fmt(fuelBalance())} لتر")
        out.add("متوسط تكلفة لتر الديزل: ${fmt(fuelAverageCost())} ريال")
        return out
    }

    fun addOpeningStock(category: String, owner: String, item: String, qty: Double) = addOpening(category, owner, item, qty)
    fun addFuelPurchaseUi(liters: Double, total: Double) = addFuelPurchase(liters, total, "توريد ديزل")
    fun auditRows(): List<String> {
        val out= mutableListOf<String>(); val c=readableDatabase.rawQuery("SELECT created_at,entity,action,entity_id,reason FROM audit_log ORDER BY id DESC LIMIT 50",null)
        while(c.moveToNext()) out.add("${c.getString(0)} | ${c.getString(1)} | ${c.getString(2)} | ${c.getString(4)}")
        c.close(); return out
    }

    companion object {
        fun now() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        fun fmt(n: Double) = String.format(java.util.Locale.US, "%.2f", n)
    }
}
