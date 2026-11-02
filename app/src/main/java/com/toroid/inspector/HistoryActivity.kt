package com.toroid.inspector

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        setSupportActionBar(findViewById(R.id.toolbar_history))
        supportActionBar?.title = "Inspection History"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        db = AppDatabase.getInstance(this)
        adapter = HistoryAdapter { record ->
            showDeleteDialog(record)
        }

        val rv = findViewById<RecyclerView>(R.id.rv_history)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        lifecycleScope.launch {
            db.measurementDao().getAll().collect { records ->
                adapter.submitList(records)
                val tvEmpty = findViewById<TextView>(R.id.tv_empty)
                tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.history_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_clear_all) {
            AlertDialog.Builder(this)
                .setTitle("Clear History")
                .setMessage("Delete all inspection records?")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch { db.measurementDao().deleteAll() }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showDeleteDialog(record: MeasurementRecord) {
        AlertDialog.Builder(this)
            .setTitle("Delete Record")
            .setMessage("Delete this measurement?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch { db.measurementDao().delete(record.id) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

// ── Adapter ───────────────────────────────────────────────────────────────────
class HistoryAdapter(
    private val onLongClick: (MeasurementRecord) -> Unit
) : ListAdapter<MeasurementRecord, HistoryAdapter.VH>(DIFF) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate    = view.findViewById<TextView>(R.id.tv_hist_date)
        val tvOd      = view.findViewById<TextView>(R.id.tv_hist_od)
        val tvId      = view.findViewById<TextView>(R.id.tv_hist_id)
        val tvHt      = view.findViewById<TextView>(R.id.tv_hist_ht)
        val tvOverall = view.findViewById<TextView>(R.id.tv_hist_overall)
        val tvPart    = view.findViewById<TextView>(R.id.tv_hist_part)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rec = getItem(position)
        val sdf = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
        holder.tvDate.text    = sdf.format(Date(rec.timestamp))
        holder.tvOd.text      = rec.odMm?.let { "OD ${String.format("%.2f", it)}" } ?: "OD –"
        holder.tvId.text      = rec.idMm?.let { "ID ${String.format("%.2f", it)}" } ?: "ID –"
        holder.tvHt.text      = rec.htMm?.let { "HT ${String.format("%.2f", it)}" } ?: "HT –"
        holder.tvOverall.text = rec.overall
        holder.tvPart.text    = rec.partNumber

        val ctx = holder.itemView.context
        val col = when (rec.overall) {
            "PASS" -> ctx.getColor(R.color.color_pass)
            "FAIL" -> ctx.getColor(R.color.color_fail)
            "WARN" -> ctx.getColor(R.color.color_warn)
            else   -> ctx.getColor(R.color.color_muted)
        }
        holder.tvOverall.setTextColor(col)
        holder.itemView.setOnLongClickListener { onLongClick(rec); true }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<MeasurementRecord>() {
            override fun areItemsTheSame(a: MeasurementRecord, b: MeasurementRecord) = a.id == b.id
            override fun areContentsTheSame(a: MeasurementRecord, b: MeasurementRecord) = a == b
        }
    }
}
