package com.example.filemover

import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoreUris()

        binding.sourceBtn.setOnClickListener { pickDirectory(REQ_SOURCE) }
        binding.destBtn.setOnClickListener { pickDirectory(REQ_DEST) }
        binding.moveBtn.setOnClickListener { startMove() }
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

    private fun pickDirectory(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, requestCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK || data?.data == null) return

        val uri = data.data!!

        // 持久化权限，下次打开 App 不用重新选
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        val prefs = getPreferences(MODE_PRIVATE)

        when (requestCode) {
            REQ_SOURCE -> {
                sourceUri = uri
                prefs.edit().putString(prefSourceKey, uri.toString()).apply()
                updateSourceLabel(uri)
            }
            REQ_DEST -> {
                destUri = uri
                prefs.edit().putString(prefDestKey, uri.toString()).apply()
                updateDestLabel(uri)
            }
        }
    }

    private fun updateSourceLabel(uri: Uri) {
        val name = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: "已选择"
        binding.sourcePath.text = name
    }

    private fun updateDestLabel(uri: Uri) {
        val name = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: "已选择"
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

        // 只取当前目录下的文件（不递归子目录）
        val files = srcDir.listFiles().filter { it.isFile }
        var success = 0
        var failed = 0

        for (file in files) {
            try {
                val movedUri = DocumentsContract.moveDocument(
                    contentResolver,
                    file.uri,
                    file.uri,                   // parent
                    dstUri                      // target parent
                )
                if (movedUri != null) {
                    success++
                } else {
                    // moveDocument 返回 null，尝试手动复制+删除
                    if (copyAndDelete(file, dstDir)) {
                        success++
                    } else {
                        failed++
                    }
                }
            } catch (e: Exception) {
                // moveDocument 失败时降级为 copyAndDelete
                if (copyAndDelete(file, dstDir)) {
                    success++
                } else {
                    failed++
                }
            }
        }

        return MoveResult(success, failed, files.size)
    }

    /**
     * 降级方案：当 DocumentsContract.moveDocument 不可用时，
     * 使用 contentResolver 手动复制然后删除。
     */
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

            // 复制成功后删除源文件
            srcFile.delete()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    companion object {
        private const val REQ_SOURCE = 1001
        private const val REQ_DEST = 1002
    }
}
