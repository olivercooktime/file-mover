package com.example.filemover

import android.app.ProgressDialog
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

    private val prefSourceKey = "source_uri"
    private val prefDestKey = "dest_uri"

    private val sourcePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        log("sourcePicker 回调: resultCode=${result.resultCode}, data=${result.data}")
        if (result.resultCode == RESULT_OK && result.data?.data != null) {
            val uri = result.data!!.data!!
            log("源目录已选择: $uri")
            takePersist(uri)
            sourceUri = uri
            getPreferences(MODE_PRIVATE).edit().putString(prefSourceKey, uri.toString()).apply()
            updateSourceLabel(uri)
        }
    }

    private val destPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        log("destPicker 回调: resultCode=${result.resultCode}, data=${result.data}")
        if (result.resultCode == RESULT_OK && result.data?.data != null) {
            val uri = result.data!!.data!!
            log("目标目录已选择: $uri")
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

        // 版本号
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            binding.versionText.text = "v${info.versionName} (build ${info.versionCode})"
        } catch (e: Exception) {
            binding.versionText.text = "v?"
        }

        log("onCreate: 版本=${binding.versionText.text}")

        restoreUris()

        binding.sourceBtn.setOnClickListener {
            log("点击 选择源目录")
            openTree(sourcePicker)
        }
        binding.destBtn.setOnClickListener {
            log("点击 选择目标目录")
            openTree(destPicker)
        }
        binding.moveBtn.setOnClickListener { startMove() }
    }

    private fun openTree(picker: androidx.activity.result.ActivityResultLauncher<Intent>) {
        // ACTION_OPEN_DOCUMENT_TREE 不需要任何特殊 flag，
        // FLAG_GRANT_* 是用于跨 App 授权，放在这里反而干扰系统选择器
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try {
            log("启动文件选择器...")
            picker.launch(intent)
            log("选择器已启动")
        } catch (e: Exception) {
            log("启动选择器异常: ${e.message}")
            Toast.makeText(this, "无法打开文件选择器: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun log(msg: String) {
        binding.logText.text = "${binding.logText.text}\n$msg"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun takePersist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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
