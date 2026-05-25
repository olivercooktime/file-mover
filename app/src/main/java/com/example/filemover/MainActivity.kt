package com.example.filemover

import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
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
    private var pickingFor = ""

    private val prefSourceKey = "source_uri"
    private val prefDestKey = "dest_uri"

    // 使用 StartActivityForResult 以便完全控制 Intent，
    // 绕开 OpenDocumentTree 在小米 HyperOS 上的缓存 bug
    private val picker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        log("回调: resultCode=${result.resultCode}, data=${result.data?.data}")
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) {
            log("用户取消或选择器退出")
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}

        when (pickingFor) {
            "source" -> {
                sourceUri = uri
                getPreferences(MODE_PRIVATE).edit().putString(prefSourceKey, uri.toString()).apply()
                updateSourceLabel(uri)
                log("源目录已选择: ${uri.lastPathSegment}")
            }
            "dest" -> {
                destUri = uri
                getPreferences(MODE_PRIVATE).edit().putString(prefDestKey, uri.toString()).apply()
                updateDestLabel(uri)
                log("目标目录已选择: ${uri.lastPathSegment}")
            }
        }
        pickingFor = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            binding.versionText.text = "v${info.versionName} (build ${info.versionCode})"
        } catch (e: Exception) {
            binding.versionText.text = "v?"
        }
        log("版本=${binding.versionText.text}")
        log("厂商=${android.os.Build.MANUFACTURER} 型号=${android.os.Build.MODEL} SDK=${android.os.Build.VERSION.SDK_INT}")

        restoreUris()

        binding.sourceBtn.setOnClickListener {
            pickingFor = "source"
            log("启动源目录选择器...")
            picker.launch(buildTreeIntent())
        }
        binding.destBtn.setOnClickListener {
            pickingFor = "dest"
            log("启动目标目录选择器...")
            picker.launch(buildTreeIntent())
        }
        binding.moveBtn.setOnClickListener { startMove() }

        binding.copyLogBtn.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("log", binding.logText.text))
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
        }

        binding.clearLogBtn.setOnClickListener {
            binding.logText.text = "(等待操作...)"
        }
    }

    private fun buildTreeIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
    }

    private fun log(msg: String) {
        binding.logText.text = "${binding.logText.text}\n$msg"
        binding.logScrollView.post {
            binding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
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
        binding.sourceCheck.text = "✓"
        binding.sourceCard.strokeWidth = 3
        binding.sourceCard.strokeColor = 0xFF_4CAF50.toInt()
    }

    private fun updateDestLabel(uri: Uri) {
        val name = DocumentFile.fromTreeUri(this, uri)?.name
            ?: uri.lastPathSegment ?: "已选择"
        binding.destPath.text = name
        binding.destCheck.text = "✓"
        binding.destCard.strokeWidth = 3
        binding.destCard.strokeColor = 0xFF_4CAF50.toInt()
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

        log("开始移动: $src -> $dst")

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

            log(msg)

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
                    srcUri,
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
                contentResolver.openOutputStream(newFile.uri, "w")?.use { output ->
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
