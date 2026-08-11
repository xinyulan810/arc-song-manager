package com.arcaea.songpack.manager

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.arcaea.songpack.R
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
                Toast.makeText(this@BackupActivity, getString(R.string.manual_backup_created), Toast.LENGTH_SHORT).show()
                refresh()
            } else {
                Toast.makeText(this@BackupActivity, getString(R.string.backup_failed, err), Toast.LENGTH_LONG).show()
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
            .setTitle(getString(R.string.restore_snapshot, time))
            .setMessage(getString(R.string.restore_snapshot_msg, content.trim()))
            .setPositiveButton(getString(R.string.restore)) { _, _ ->
                doRestore(snap)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
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
                    getString(R.string.restored_to, BackupManager.formatTime(snap.timestamp)),
                    Toast.LENGTH_LONG
                ).show()
                refresh()
            } else {
                Toast.makeText(this@BackupActivity, getString(R.string.restore_failed, err), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDelete(snap: BackupManager.Snapshot) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_snapshot))
            .setMessage(getString(R.string.delete_snapshot_msg, BackupManager.formatTime(snap.timestamp)))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        BackupManager.delete(this@BackupActivity, snap)
                    }
                    refresh()
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .show()
    }
}
