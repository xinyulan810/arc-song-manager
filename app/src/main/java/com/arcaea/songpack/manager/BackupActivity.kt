package com.arcaea.songpack.manager

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.arcaea.songpack.databinding.ActivityBackupBinding
import com.arcaea.songpack.manager.ui.BackupAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份与还原页
 *
 * - 手动创建还原点
 * - 点击快照 → 恢复(恢复前自动保存当前状态)
 * - 长按快照 → 删除
 */
class BackupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupBinding

    private val adapter by lazy {
        BackupAdapter(
            onClick = { snap -> confirmRestore(snap) },
            onLongClick = { snap -> confirmDelete(snap) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.backupList.layoutManager = LinearLayoutManager(this)
        binding.backupList.adapter = adapter

        binding.fabBackup.setOnClickListener {
            doManualBackup()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val snaps = withContext(Dispatchers.IO) {
                BackupManager.listSnapshots(this@BackupActivity)
            }
            adapter.submit(snaps)
            binding.emptyView.visibility = if (snaps.isEmpty()) View.VISIBLE else View.GONE
            binding.backupList.visibility = if (snaps.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun doManualBackup() {
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                BackupManager.snapshot(this@BackupActivity, BackupManager.SnapshotType.MANUAL, "手动备份")
            }
            if (err == null) {
                Toast.makeText(this@BackupActivity, "已创建手动备份", Toast.LENGTH_SHORT).show()
                refresh()
            } else {
                Toast.makeText(this@BackupActivity, "备份失败: $err", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmRestore(snap: BackupManager.Snapshot) {
        val time = BackupManager.formatTime(snap.timestamp)
        val content = buildString {
            if (snap.hasSonglist) append("songlist\n")
            if (snap.hasPacklist) append("packlist")
        }
        AlertDialog.Builder(this)
            .setTitle("恢复快照 $time")
            .setMessage(
                "将用该快照覆盖当前 ${content.trim()}\n\n" +
                    "恢复前会自动备份当前状态，可随时再还原。\n" +
                    "恢复后需返回上层刷新查看。"
            )
            .setPositiveButton("恢复") { _, _ ->
                doRestore(snap)
            }
            .setNegativeButton("取消") { _, _ -> }
            .show()
    }

    private fun doRestore(snap: BackupManager.Snapshot) {
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                BackupManager.restore(this@BackupActivity, snap)
            }
            if (err == null) {
                Toast.makeText(
                    this@BackupActivity,
                    "已恢复到 ${BackupManager.formatTime(snap.timestamp)}",
                    Toast.LENGTH_LONG
                ).show()
                refresh()
            } else {
                Toast.makeText(this@BackupActivity, "恢复失败: $err", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDelete(snap: BackupManager.Snapshot) {
        AlertDialog.Builder(this)
            .setTitle("删除快照")
            .setMessage("确定删除 ${BackupManager.formatTime(snap.timestamp)} 的快照？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        BackupManager.delete(this@BackupActivity, snap)
                    }
                    refresh()
                }
            }
            .setNegativeButton("取消") { _, _ -> }
            .show()
    }
}
