package com.example.filemover

import android.app.ProgressDialog
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.example.filemover.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var sourceUri: Uri? = null
    private var destUri: Uri? = null

    private val prefSourceKey = "source_uri"
    private val prefDestKey = "dest_uri"

    private val sourcePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            takePersist(uri)
            sourceUri = uri
            getPreferences(MODE_PRIVATE).edit().putString(prefSourceKey, uri.toString()).apply()
            updateSourceLabel(uri)
        }
    }

    private val destPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            takePersist(uri)
            destUri = uri
            getPreferences(MODE_PRIVATE).edit().putString(prefDestKey, uri.toString()).apply()
            updateDestLabel(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoreUris()

        binding.sourceBtn.setOnClickListener { sourcePicker.launch(null) }
        binding.destBtn.setOnClickListener { destPicker.launch(null) }
        binding.moveBtn.setOnClickListener { startMove() }
    }

    private fun takePersist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
    }

    private fun restoreUris() {
        val prefs = getPreferences(MODE_PRIVATE)
        prefs.getString(prefSourceKey, null)?.let {
            sourceUri = Uri.parse(it)
            updateSourceLabel(sourceUri!!)
        }
        prefs.getString(prefDestKey, null)?.let {
            destUri = Uri.parse(it)
            updateDestLabel(destUri!!)
        }
    }

    private fun updateSourceLabel(uri: Uri) {
        val name = DocumentFile.fromTreeUri(this, uri)?.name
            ?: uri.lastPathSegment ?: "已选择"
        binding.sourcePath.text = name
    }

    private fun updateDestLabel(uri: Uri) {
        val name = DocumentFile.fromTreeUri(this, uri)?.name
            ?: uri.lastPathSegment ?: "已选择"
        binding.destPath.text = name
    }

    private fun startMove() {
        val src = sourceUri
        val dst = destUri

        if (src == null) {
            binding.sourcePath.error = "请先选择源目录"
            return
        }
        if (dst == null) {
            binding.destPath.error = "请先选择目标目录"
            return
        }

        val dialog = ProgressDialog(this).apply {
            setTitle(getString(R.string.moving_title))
            setMessage(getString(R.string.moving_message))
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                moveFiles(src, dst)
            }

            dialog.dismiss()

            val msg = when {
                result.total == 0 -> "源目录中没有文件"
                result.failed == 0 -> "全部 ${result.success} 个文件移动完成"
                else -> "${result.success} 个成功，${result.failed} 个失败"
            }

            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.move_done))
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    data class MoveResult(val success: Int, val failed: Int, val total: Int)

    private fun moveFiles(srcUri: Uri, dstUri: Uri): MoveResult {
        val srcDir = DocumentFile.fromTreeUri(this, srcUri) ?: return MoveResult(0, 0, 0)
        val dstDir = DocumentFile.fromTreeUri(this, dstUri) ?: return MoveResult(0, 0, 0)

        val files = srcDir.listFiles().filter { it.isFile }
        var success = 0
        var failed = 0

        for (file in files) {
            try {
                val movedUri = DocumentsContract.moveDocument(
                    contentResolver,
                    file.uri,
                    file.uri,
                    dstUri
                )
                if (movedUri != null) {
                    success++
                } else {
                    if (copyAndDelete(file, dstDir)) success++ else failed++
                }
            } catch (_: Exception) {
                if (copyAndDelete(file, dstDir)) success++ else failed++
            }
        }

        return MoveResult(success, failed, files.size)
    }

    private fun copyAndDelete(srcFile: DocumentFile, dstDir: DocumentFile): Boolean {
        return try {
            val newFile = dstDir.createFile(
                srcFile.type ?: "application/octet-stream",
                srcFile.name ?: "unknown"
            ) ?: return false

            contentResolver.openInputStream(srcFile.uri)?.use { input ->
                contentResolver.openOutputStream(newFile.uri, "wt")?.use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                newFile.delete()
                return false
            }

            srcFile.delete()
            true
        } catch (e: Exception) {
            false
        }
    }
}
