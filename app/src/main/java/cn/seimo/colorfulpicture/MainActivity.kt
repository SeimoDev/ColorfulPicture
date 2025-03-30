package cn.seimo.colorfulpicture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import cn.seimo.colorfulpicture.databinding.ActivityMainBinding
import java.io.FileNotFoundException
import android.media.ExifInterface
import android.util.Log
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import android.content.ContentValues
import androidx.core.content.FileProvider

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedImageUri: Uri? = null
    private var selectedFrameColor: Int = Color.WHITE
    private var dominantColor: Int = Color.WHITE
    private var originalBitmap: Bitmap? = null // 保存原始图片
    private var isPreviewMode = true // 控制预览模式
    private var selectedColorView: View? = null // 跟踪当前选中的颜色视图
    private var themeColor: Int = Color.BLUE // 默认主题色
    private var isCornerEnabled = false // 是否启用圆角
    private var cornerRadiusPercent = 20 // 圆角大小百分比
    private var lastGeneratedImagePath: String? = null // 保存最后生成的图片路径

    // 用于请求单个权限
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // 如果是从选择图片按钮触发的，则打开图片选择器
            if (permissionRequestReason == REASON_PICK_IMAGE) {
                // 检查是否有分享的图片等待处理
                val shareUri = selectedImageUri
                if (shareUri != null && intent.action?.startsWith("android.intent.action.SEND") == true) {
                    // 有分享的图片等待处理
                    loadImage(shareUri)
                } else {
                    // 没有分享的图片，打开图片选择器
                    openImagePicker()
                }
            } else if (permissionRequestReason == REASON_SAVE_IMAGE) {
                saveImageToGallery()
            }
        } else {
            Toast.makeText(this, "需要相应权限才能完成操作", Toast.LENGTH_SHORT).show()
        }
    }

    // 用于请求多个权限
    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var allGranted = true
        permissions.entries.forEach {
            if (!it.value) {
                allGranted = false
            }
        }
        
        if (allGranted) {
            Toast.makeText(this, "所有权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "某些权限被拒绝，应用功能可能受限", Toast.LENGTH_SHORT).show()
        }
    }

    // 图片选择请求
    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            loadImage(it)
        }
    }

    // 权限请求原因
    private var permissionRequestReason = REASON_NONE

    companion object {
        private const val REASON_NONE = 0
        private const val REASON_PICK_IMAGE = 1
        private const val REASON_SAVE_IMAGE = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        
        // 获取应用主题色
        val typedValue = TypedValue()
        if (theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)) {
            themeColor = typedValue.data
        } else if (theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)) {
            themeColor = typedValue.data
        }
        
        // 在启动时检查所有权限
        checkAndRequestAllPermissions()
        
        setupListeners()
        
        // 处理接收到的分享图片
        handleReceivedImageIntent(intent)
        
        // 从保存的状态恢复数据
        if (savedInstanceState != null) {
            restoreState(savedInstanceState)
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 处理新接收到的分享图片
        handleReceivedImageIntent(intent)
    }
    
    /**
     * 处理接收到的图片分享Intent
     */
    private fun handleReceivedImageIntent(intent: Intent) {
        val action = intent.action
        val type = intent.type
        
        if ((Intent.ACTION_SEND == action || Intent.ACTION_SEND_MULTIPLE == action) && type?.startsWith("image/") == true) {
            try {
                if (Intent.ACTION_SEND == action) {
                    // 处理单张图片
                    val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                    }
                    
                    imageUri?.let {
                        // 需要权限，检查并请求
                        if (checkAndRequestImagePermission()) {
                            // 已有权限，直接加载图片
                            selectedImageUri = it
                            loadImage(it)
                            Toast.makeText(this, "已接收分享的图片", Toast.LENGTH_SHORT).show()
                        } else {
                            // 在权限授予后会从onCreate/onResume调用，不需额外处理
                            permissionRequestReason = REASON_PICK_IMAGE
                        }
                    }
                } else if (Intent.ACTION_SEND_MULTIPLE == action) {
                    // 处理多张图片（只取第一张）
                    val imageUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    }
                    
                    if (!imageUris.isNullOrEmpty()) {
                        val firstImageUri = imageUris[0]
                        // 需要权限，检查并请求
                        if (checkAndRequestImagePermission()) {
                            // 已有权限，直接加载图片
                            selectedImageUri = firstImageUri
                            loadImage(firstImageUri)
                            Toast.makeText(this, "已接收分享的图片（仅使用第一张）", Toast.LENGTH_SHORT).show()
                        } else {
                            // 在权限授予后会从onCreate/onResume调用，不需额外处理
                            permissionRequestReason = REASON_PICK_IMAGE
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ImageShare", "处理分享图片失败: ${e.message}")
                Toast.makeText(this, "处理分享图片时出错", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // 保存状态
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        
        // 保存所选图片URI
        if (selectedImageUri != null) {
            outState.putString("selectedImageUri", selectedImageUri.toString())
        }
        
        // 保存颜色和模式设置
        outState.putInt("selectedFrameColor", selectedFrameColor)
        outState.putInt("dominantColor", dominantColor)
        outState.putBoolean("isPreviewMode", isPreviewMode)
        
        // 保存圆角设置
        outState.putBoolean("isCornerEnabled", isCornerEnabled)
        outState.putInt("cornerRadiusPercent", cornerRadiusPercent)
        
        // 保存文本信息
        outState.putString("cameraModel", binding.etCameraModel.text.toString())
        outState.putString("photoInfo", binding.etPhotoInfo.text.toString())
    }
    
    // 恢复状态
    private fun restoreState(savedInstanceState: Bundle) {
        // 恢复所选图片URI
        val uriString = savedInstanceState.getString("selectedImageUri")
        if (uriString != null) {
            selectedImageUri = Uri.parse(uriString)
            // 重新加载图片，但不提取颜色（避免重复工作）
            selectedImageUri?.let { loadImageFromSavedState(it) }
        }
        
        // 恢复颜色和模式设置
        selectedFrameColor = savedInstanceState.getInt("selectedFrameColor", Color.WHITE)
        dominantColor = savedInstanceState.getInt("dominantColor", Color.WHITE)
        isPreviewMode = savedInstanceState.getBoolean("isPreviewMode", true)
        
        // 恢复圆角设置
        isCornerEnabled = savedInstanceState.getBoolean("isCornerEnabled", false)
        cornerRadiusPercent = savedInstanceState.getInt("cornerRadiusPercent", 20)
        
        // 更新圆角UI状态
        binding.switchCorner.isChecked = isCornerEnabled
        binding.seekBarCornerRadius.progress = cornerRadiusPercent
        binding.seekBarCornerRadius.isEnabled = isCornerEnabled
        binding.tvCornerRadius.alpha = if (isCornerEnabled) 1.0f else 0.5f
        binding.tvCornerRadiusValue.alpha = if (isCornerEnabled) 1.0f else 0.5f
        binding.tvCornerRadiusValue.text = "$cornerRadiusPercent%"
        
        // 恢复文本信息
        binding.etCameraModel.setText(savedInstanceState.getString("cameraModel", ""))
        binding.etPhotoInfo.setText(savedInstanceState.getString("photoInfo", ""))
        
        // 更新预览模式标签
        binding.tvPreviewLabel.text = if (isPreviewMode) "实时预览 (点击切换)" else "已暂停预览 (点击切换)"
    }
    
    // 从已保存的状态加载图片，避免重复提取颜色
    private fun loadImageFromSavedState(uri: Uri) {
        try {
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
            originalBitmap = bitmap
            
            if (isPreviewMode) {
                updatePreview()
            } else {
                binding.imagePreview.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "加载图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupListeners() {
        binding.btnSelectImage.setOnClickListener {
            // 检查并请求图片访问权限
            permissionRequestReason = REASON_PICK_IMAGE
            if (checkAndRequestImagePermission()) {
                openImagePicker()
            }
        }
        
        binding.btnGenerate.setOnClickListener {
            if (selectedImageUri != null) {
                permissionRequestReason = REASON_SAVE_IMAGE
                if (checkAndRequestStoragePermission()) {
                    saveImageToGallery()
                }
            } else {
                Toast.makeText(this, "请先选择一张图片", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 添加分享按钮点击事件
        binding.btnShare.setOnClickListener {
            if (selectedImageUri != null) {
                if (lastGeneratedImagePath != null) {
                    // 已有生成的图片，直接分享
                    shareImage(File(lastGeneratedImagePath!!))
                } else {
                    // 生成图片并分享
                    generateAndShareImage()
                }
            } else {
                Toast.makeText(this, "请先选择一张图片", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 文本框获取焦点时全选内容
        binding.etCameraModel.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && binding.etCameraModel.text?.isNotEmpty() == true) {
                binding.etCameraModel.selectAll()
            }
        }
        
        binding.etPhotoInfo.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && binding.etPhotoInfo.text?.isNotEmpty() == true) {
                binding.etPhotoInfo.selectAll()
            }
        }
        
        // 添加文本变化监听器，实时更新预览
        binding.etCameraModel.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updatePreviewTexts()
                if (isPreviewMode) {
                    updatePreview()
                }
            }
        })
        
        binding.etPhotoInfo.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updatePreviewTexts()
                if (isPreviewMode) {
                    updatePreview()
                }
            }
        })
        
        // 点击预览图片切换预览模式
        binding.imagePreview.setOnClickListener {
            togglePreviewMode()
        }
        
        // 点击预览标签也可以切换预览模式
        binding.tvPreviewLabel.setOnClickListener {
            togglePreviewMode()
        }
        
        // 圆角开关监听器
        binding.switchCorner.setOnCheckedChangeListener { _, isChecked ->
            isCornerEnabled = isChecked
            
            // 更新圆角相关控件的可用状态
            binding.seekBarCornerRadius.isEnabled = isChecked
            binding.tvCornerRadius.alpha = if (isChecked) 1.0f else 0.5f
            binding.tvCornerRadiusValue.alpha = if (isChecked) 1.0f else 0.5f
            
            // 如果在预览模式下，立即更新预览
            if (isPreviewMode) {
                updatePreview()
            }
        }
        
        // 圆角大小拖动条监听器
        binding.seekBarCornerRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                cornerRadiusPercent = progress
                binding.tvCornerRadiusValue.text = "$progress%"
                
                // 如果在预览模式下，立即更新预览
                if (isPreviewMode && fromUser) {
                    updatePreview()
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 初始化圆角控件状态
        binding.seekBarCornerRadius.progress = cornerRadiusPercent
        binding.tvCornerRadiusValue.text = "$cornerRadiusPercent%"
        binding.switchCorner.isChecked = isCornerEnabled
        binding.seekBarCornerRadius.isEnabled = isCornerEnabled
        binding.tvCornerRadius.alpha = if (isCornerEnabled) 1.0f else 0.5f
        binding.tvCornerRadiusValue.alpha = if (isCornerEnabled) 1.0f else 0.5f
    }
    
    // 打开图片选择器
    private fun openImagePicker() {
        getContent.launch("image/*")
    }
    
    // 检查并请求所有需要的权限
    private fun checkAndRequestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        // 根据Android版本请求不同的权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13及以上需要READ_MEDIA_IMAGES权限
            if (ContextCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.READ_MEDIA_IMAGES
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            // 低版本Android需要读写外部存储权限
            if (ContextCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            
            if (ContextCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        
        // 如果有需要请求的权限，则请求
        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
    
    // 检查并请求图片访问权限
    private fun checkAndRequestImagePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13及以上
            if (ContextCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.READ_MEDIA_IMAGES
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                false
            } else {
                true
            }
        } else {
            // Android 12及以下
            if (ContextCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                false
            } else {
                true
            }
        }
    }
    
    // 检查并请求存储权限
    private fun checkAndRequestStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13及以上使用照片媒体库不需要特殊权限
            true
        } else {
            // Android 12及以下需要写入外部存储权限
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                false
            } else {
                true
            }
        }
    }
    
    private fun loadImage(uri: Uri) {
        try {
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
            originalBitmap = bitmap // 保存原始图片
            
            // 先显示原始图片
            binding.imagePreview.setImageBitmap(bitmap)
            
            // 尝试从图片EXIF信息中读取相机和照片信息
            readExifData(uri)
            
            // 更新预览中的文本信息（此方法现在为空操作）
            updatePreviewTexts()
            
            // 设置预览模式标签
            isPreviewMode = true
            binding.tvPreviewLabel.text = "正在加载预览..."
            
            // 使用Palette库分析图片颜色，并在完成后更新预览
            Palette.from(bitmap).generate { palette ->
                palette?.let {
                    // 清除旧的颜色选项
                    binding.colorContainer.removeAllViews()
                    
                    // 提取所有可能的颜色
                    val colors = mutableListOf<Int>()
                    
                    // 首先尝试添加明亮的颜色
                    it.lightVibrantSwatch?.rgb?.let { color -> colors.add(color) }
                    it.lightMutedSwatch?.rgb?.let { color -> colors.add(color) }
                    
                    // 其次添加中等亮度的颜色
                    it.vibrantSwatch?.rgb?.let { color -> colors.add(color) }
                    it.mutedSwatch?.rgb?.let { color -> colors.add(color) }
                    
                    // 最后添加暗色
                    it.darkVibrantSwatch?.rgb?.let { color -> colors.add(color) }
                    it.darkMutedSwatch?.rgb?.let { color -> colors.add(color) }
                    
                    // 获取主色调（默认值）
                    dominantColor = it.getDominantColor(Color.WHITE)
                    colors.add(dominantColor)
                    
                    // 按亮度排序，优先选择浅色
                    colors.sortByDescending { color ->
                        val hsl = FloatArray(3)
                        ColorUtils.colorToHSL(color, hsl)
                        hsl[2] // 按亮度排序
                    }
                    
                    // 选择排序后的第一个颜色（最浅的）作为默认选中色
                    if (colors.isNotEmpty()) {
                        selectedFrameColor = colors[0]
                    }
                    
                    // 添加所有提取的颜色
                    colors.forEach { color ->
                        addColorToContainer(color)
                    }
                    
                    // 添加主色调的变体颜色
                    if (colors.isNotEmpty()) {
                        addColorVariations(colors[0])
                    }
                    
                    // 在UI线程上更新预览标签和预览图像
                    runOnUiThread {
                        binding.tvPreviewLabel.text = "实时预览 (点击切换)"
                        // 颜色分析完成后更新预览
                        updatePreview()
                    }
                }
            }
        } catch (e: FileNotFoundException) {
            Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "发生错误: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 尝试从图片的EXIF数据中读取相机和照片信息
     */
    private fun readExifData(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val exifInterface = ExifInterface(inputStream)
                
                // 读取相机制造商和型号
                val make = exifInterface.getAttribute(ExifInterface.TAG_MAKE) ?: ""
                val model = exifInterface.getAttribute(ExifInterface.TAG_MODEL) ?: ""
                
                // 读取焦距
                val focalLength = exifInterface.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                val focalLengthValue = if (focalLength != null) {
                    val parts = focalLength.split("/")
                    if (parts.size == 2) {
                        try {
                            val num = parts[0].toFloat()
                            val den = parts[1].toFloat()
                            if (den != 0f) "${(num / den).toInt()}mm" else ""
                        } catch (e: NumberFormatException) {
                            ""
                        }
                    } else {
                        focalLength
                    }
                } else ""
                
                // 读取光圈值
                val aperture = exifInterface.getAttribute(ExifInterface.TAG_F_NUMBER)
                val apertureValue = if (aperture != null) {
                    val parts = aperture.split("/")
                    if (parts.size == 2) {
                        try {
                            val num = parts[0].toFloat()
                            val den = parts[1].toFloat()
                            if (den != 0f) "f/${(num / den)}" else ""
                        } catch (e: NumberFormatException) {
                            ""
                        }
                    } else {
                        "f/$aperture"
                    }
                } else ""
                
                // 读取快门速度
                val shutterSpeed = exifInterface.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                val shutterSpeedValue = if (shutterSpeed != null) {
                    val floatValue = shutterSpeed.toFloatOrNull()
                    if (floatValue != null && floatValue > 0) {
                        if (floatValue >= 1) {
                            "${floatValue}s"
                        } else {
                            "1/${(1 / floatValue).toInt()}s"
                        }
                    } else {
                        ""
                    }
                } else ""
                
                // 读取ISO值 - 兼容不同Android版本
                val iso = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                        // Android 7.0及以上版本
                        exifInterface.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS) ?: ""
                    }
                    else -> {
                        // 低版本Android
                        exifInterface.getAttribute("ISOSpeedRatings") ?: ""
                    }
                }
                val isoValue = if (iso.isNotEmpty()) "ISO$iso" else ""
                
                // 组合相机信息
                val cameraInfo = if (make.isNotEmpty() && model.isNotEmpty()) {
                    "$make | $model"
                } else if (model.isNotEmpty()) {
                    model
                } else if (make.isNotEmpty()) {
                    make
                } else {
                    "" // 保持为空
                }
                
                // 组合照片信息
                val photoInfoParts = listOf(focalLengthValue, apertureValue, shutterSpeedValue, isoValue)
                    .filter { it.isNotEmpty() }
                val photoInfo = photoInfoParts.joinToString(" ")
                
                // 更新UI
                runOnUiThread {
                    binding.etCameraModel.setText(cameraInfo)
                    binding.etPhotoInfo.setText(photoInfo)
                }
            }
        } catch (e: Exception) {
            Log.e("ExifReader", "读取EXIF信息失败: ${e.message}")
            // 读取失败时，设置为空字符串
            binding.etCameraModel.setText("")
            binding.etPhotoInfo.setText("")
        }
    }
    
    private fun updatePreviewTexts() {
        // 不再更新预览中的文本，因为视图已移除
        // 保留此方法以兼容现有代码，后续可考虑移除
    }
    
    private fun generateColorPalette(bitmap: Bitmap) {
        // 此方法不再使用，逻辑已移至loadImage方法中
    }
    
    private fun addColorVariations(color: Int) {
        // 生成色相变化的颜色
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        
        // 生成不同亮度的变体（偏向浅色）
        for (i in 1..3) {
            val newHsl = hsl.clone()
            newHsl[2] = minOf(0.5f + (i * 0.15f), 0.95f) // 调整亮度，确保偏向浅色
            addColorToContainer(ColorUtils.HSLToColor(newHsl))
        }
        
        // 生成不同色相的变体
        for (i in 1..5) {
            val newHsl = hsl.clone()
            newHsl[0] = (newHsl[0] + i * 30) % 360 // 调整色相
            // 确保亮度适中偏浅
            newHsl[2] = minOf(maxOf(newHsl[2], 0.6f), 0.85f)
            addColorToContainer(ColorUtils.HSLToColor(newHsl))
        }
    }
    
    private fun addColorToContainer(color: Int) {
        val colorView = View(this)
        val params = LinearLayout.LayoutParams(100, 100)
        params.marginEnd = 16
        colorView.layoutParams = params
        
        // 创建圆角矩形背景
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.RECTANGLE
        shape.cornerRadius = 8f // 设置圆角半径
        shape.setColor(color) // 设置颜色
        
        // 默认边框样式
        shape.setStroke(2, Color.LTGRAY) // 浅灰色细边框
        
        colorView.background = shape
        
        // 给颜色视图添加点击事件
        colorView.setOnClickListener {
            // 更新选中颜色
            selectedFrameColor = color
            // 更新所有颜色视图的边框
            updateColorSelection(colorView)
        }
        
        // 如果该颜色与当前选中颜色相同，设置为选中状态
        if (color == selectedFrameColor) {
            updateColorSelection(colorView)
        }
        
        binding.colorContainer.addView(colorView)
    }
    
    private fun updateColorSelection(newSelectedView: View) {
        // 移除旧选中视图的高亮边框
        selectedColorView?.let { oldView ->
            val oldBackground = oldView.background as? GradientDrawable
            oldBackground?.setStroke(2, Color.LTGRAY) // 恢复为浅灰色细边框
        }
        
        // 设置新选中视图的高亮边框
        val newBackground = newSelectedView.background as? GradientDrawable
        newBackground?.setStroke(4, themeColor) // 使用主题色粗边框
        
        // 添加阴影效果 (API 21及以上)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            newSelectedView.elevation = 6f // 添加阴影
            selectedColorView?.elevation = 0f // 移除旧视图阴影
        }
        
        // 更新当前选中视图引用
        selectedColorView = newSelectedView
        
        // 更新实时预览
        if (isPreviewMode) {
            updatePreview()
        }
    }
    
    private fun saveImageToGallery() {
        try {
            selectedImageUri?.let { uri ->
                // 获取原始图片的位图
                val originalBitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                
                // 创建相框图片
                val framedBitmap = createFramedImage(originalBitmap)
                
                // 获取原始图片的EXIF数据
                var exifInterface: ExifInterface? = null
                try {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        exifInterface = ExifInterface(inputStream)
                    }
                } catch (e: Exception) {
                    Log.e("ExifSaver", "读取原始EXIF数据失败: ${e.message}")
                }
                
                // 保存图片到临时文件并应用EXIF数据
                val fileName = "ColorfulPicture_${System.currentTimeMillis()}.jpg"
                val outputDir = cacheDir // 使用应用缓存目录
                val outputFile = File(outputDir, fileName)
                
                // 将位图保存到临时文件
                val outputStream = FileOutputStream(outputFile)
                framedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                outputStream.flush()
                outputStream.close()
                
                // 将EXIF数据写入新图片
                if (exifInterface != null) {
                    try {
                        val newExif = ExifInterface(outputFile.absolutePath)
                        
                        // 复制原始EXIF标签到新图片
                        copyExifTags(exifInterface!!, newExif)
                        
                        // 保存EXIF更改
                        newExif.saveAttributes()
                    } catch (e: Exception) {
                        Log.e("ExifSaver", "写入EXIF数据失败: ${e.message}")
                    }
                }
                
                // 将图片添加到媒体库
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.TITLE, fileName)
                    put(MediaStore.Images.Media.DESCRIPTION, "使用ColorfulPicture创建的相框图片")
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                
                // 插入到媒体库并获取URI
                val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                
                if (imageUri != null) {
                    // 将位图数据写入媒体库
                    contentResolver.openOutputStream(imageUri)?.use { os ->
                        outputFile.inputStream().use { it.copyTo(os) }
                    }
                    
                    // 对于Android 10及以上版本，标记完成
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(imageUri, values, null, null)
                    }
                    
                    // 保存最后生成的图片路径（用于分享）
                    lastGeneratedImagePath = outputFile.absolutePath
                    
                    // 删除临时文件
                    outputFile.delete()
                    
                    Toast.makeText(this, "图片已保存到相册", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "保存失败: 无法创建媒体条目", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("SaveImage", "保存失败", e)
        }
    }
    
    /**
     * 生成图片并分享
     */
    private fun generateAndShareImage() {
        try {
            selectedImageUri?.let { uri ->
                // 获取原始图片的位图
                val originalBitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                
                // 创建相框图片
                val framedBitmap = createFramedImage(originalBitmap)
                
                // 获取原始图片的EXIF数据
                var exifInterface: ExifInterface? = null
                try {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        exifInterface = ExifInterface(inputStream)
                    }
                } catch (e: Exception) {
                    Log.e("ExifSaver", "读取原始EXIF数据失败: ${e.message}")
                }
                
                // 保存图片到临时文件
                val fileName = "ColorfulPicture_share_${System.currentTimeMillis()}.jpg"
                val outputDir = File(cacheDir, "images")
                if (!outputDir.exists()) {
                    outputDir.mkdirs()
                }
                val outputFile = File(outputDir, fileName)
                
                // 将位图保存到临时文件
                val outputStream = FileOutputStream(outputFile)
                framedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                outputStream.flush()
                outputStream.close()
                
                // 将EXIF数据写入分享图片
                if (exifInterface != null) {
                    try {
                        val newExif = ExifInterface(outputFile.absolutePath)
                        
                        // 复制原始EXIF标签到新图片
                        copyExifTags(exifInterface!!, newExif)
                        
                        // 保存EXIF更改
                        newExif.saveAttributes()
                    } catch (e: Exception) {
                        Log.e("ExifSaver", "写入EXIF数据到分享图片失败: ${e.message}")
                    }
                }
                
                // 保存最后生成的图片路径
                lastGeneratedImagePath = outputFile.absolutePath
                
                // 分享图片
                shareImage(outputFile)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "生成图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("ShareImage", "生成失败", e)
        }
    }
    
    /**
     * 分享图片
     */
    private fun shareImage(imageFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                this,
                "cn.seimo.colorfulpicture.fileprovider",
                imageFile
            )
            
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, contentUri)
                type = "image/jpeg"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(shareIntent, "分享图片"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("ShareImage", "分享失败", e)
        }
    }
    
    /**
     * 复制EXIF标签从源到目标
     */
    private fun copyExifTags(source: ExifInterface, target: ExifInterface) {
        // Android支持的基础EXIF标签列表（在所有支持的Android版本中都可用）
        val basicTags = arrayOf(
            // 设备信息
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            
            // 拍摄时间信息
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            
            // 拍摄参数
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_BRIGHTNESS_VALUE,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_MAX_APERTURE_VALUE,
            ExifInterface.TAG_SUBJECT_DISTANCE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_WHITE_BALANCE,
            
            // 图像信息
            ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
            
            // GPS信息
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            
            // 版权和作者信息
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_ARTIST
        )
        
        // 复制基础标签
        for (tag in basicTags) {
            try {
                val value = source.getAttribute(tag)
                if (value != null) {
                    target.setAttribute(tag, value)
                }
            } catch (e: Exception) {
                Log.d("ExifCopy", "复制基础标签[$tag]失败: ${e.message}")
            }
        }
        
        // Android 7.0 (N, API 24)及以上版本支持的额外标签
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val nAndAboveTags = arrayOf(
                    ExifInterface.TAG_IMAGE_DESCRIPTION,
                    ExifInterface.TAG_EXPOSURE_PROGRAM,
                    ExifInterface.TAG_SPECTRAL_SENSITIVITY,
                    // Android N开始支持，但有些设备可能不支持
                    "PhotoographicSensitivity", // 替代TAG_PHOTOGRAPHIC_SENSITIVITY
                    ExifInterface.TAG_OECF,
                    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                    ExifInterface.TAG_SCENE_CAPTURE_TYPE,
                    ExifInterface.TAG_GAIN_CONTROL,
                    ExifInterface.TAG_CONTRAST,
                    ExifInterface.TAG_SATURATION,
                    ExifInterface.TAG_SHARPNESS,
                    ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION,
                    ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
                    ExifInterface.TAG_IMAGE_UNIQUE_ID,
                    ExifInterface.TAG_EXIF_VERSION,
                    ExifInterface.TAG_FLASHPIX_VERSION,
                    ExifInterface.TAG_COLOR_SPACE,
                    ExifInterface.TAG_PIXEL_X_DIMENSION,
                    ExifInterface.TAG_PIXEL_Y_DIMENSION,
                    ExifInterface.TAG_COMPONENTS_CONFIGURATION,
                    ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL,
                    ExifInterface.TAG_USER_COMMENT,
                    ExifInterface.TAG_RELATED_SOUND_FILE,
                    ExifInterface.TAG_OFFSET_TIME,
                    ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                    ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
                    ExifInterface.TAG_SUBSEC_TIME,
                    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                    ExifInterface.TAG_SUBSEC_TIME_DIGITIZED
                )
                
                for (tag in nAndAboveTags) {
                    val value = source.getAttribute(tag)
                    if (value != null) {
                        target.setAttribute(tag, value)
                    }
                }
            } catch (e: Exception) {
                Log.d("ExifCopy", "复制Android N及以上标签失败: ${e.message}")
            }
        }
        
        // Android 10 (Q, API 29)及以上版本支持的标签
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val qAndAboveTags = arrayOf(
                    ExifInterface.TAG_GPS_IMG_DIRECTION,
                    ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
                    ExifInterface.TAG_GPS_TRACK,
                    ExifInterface.TAG_GPS_TRACK_REF,
                    ExifInterface.TAG_GPS_SPEED,
                    ExifInterface.TAG_GPS_SPEED_REF,
                    ExifInterface.TAG_GPS_DEST_BEARING,
                    ExifInterface.TAG_GPS_DEST_BEARING_REF,
                    ExifInterface.TAG_GPS_DEST_DISTANCE,
                    ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
                    ExifInterface.TAG_GPS_PROCESSING_METHOD,
                    ExifInterface.TAG_GPS_AREA_INFORMATION,
                    ExifInterface.TAG_GPS_DIFFERENTIAL,
                    "GPSHPositioningError", // 替代TAG_GPS_H_POSITIONING_ERROR
                    ExifInterface.TAG_INTEROPERABILITY_INDEX,
                    ExifInterface.TAG_DNG_VERSION,
                    ExifInterface.TAG_DEFAULT_CROP_SIZE
                )
                
                for (tag in qAndAboveTags) {
                    val value = source.getAttribute(tag)
                    if (value != null) {
                        target.setAttribute(tag, value)
                    }
                }
            } catch (e: Exception) {
                Log.d("ExifCopy", "复制Android Q及以上标签失败: ${e.message}")
            }
        }
        
        // Android 11 (R, API 30)及以上版本支持的标签
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // 使用字符串常量替代 ExifInterface 中的常量
                val rAndAboveTags = arrayOf(
                    "CameraOwnerName",      // 替代TAG_CAMERA_OWNER_NAME
                    "BodySerialNumber",     // 替代TAG_BODY_SERIAL_NUMBER
                    "LensSpecification",    // 替代TAG_LENS_SPECIFICATION
                    "LensMake",             // 替代TAG_LENS_MAKE
                    "LensModel",            // 替代TAG_LENS_MODEL
                    "LensSerialNumber"      // 替代TAG_LENS_SERIAL_NUMBER
                )
                
                for (tag in rAndAboveTags) {
                    val value = source.getAttribute(tag)
                    if (value != null) {
                        target.setAttribute(tag, value)
                    }
                }
            } catch (e: Exception) {
                Log.d("ExifCopy", "复制Android R及以上标签失败: ${e.message}")
            }
        }
        
        // 针对不同Android版本的ISO标签特殊处理
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            try {
                val isoValue = source.getAttribute("ISOSpeedRatings")
                if (isoValue != null) {
                    target.setAttribute("ISOSpeedRatings", isoValue)
                }
            } catch (e: Exception) {
                Log.e("ExifCopy", "复制ISO标签失败: ${e.message}")
            }
        }
        
        // 尝试复制可能的未列出标签 - 尽最大努力捕获所有可能的标签
        try {
            val additionalTags = arrayOf(
                // 一些相机特有标签
                "SerialNumber",
                "LensSerialNumber",
                "ImageNumber",
                "SonyModelID",
                "CanonModelID",
                "NikonModelID",
                "FujiFilmModelID",
                // 一些图像编辑软件可能添加的标签
                "Rating",
                "Software",
                "HostComputer"
            )
            
            for (tag in additionalTags) {
                val value = source.getAttribute(tag)
                if (value != null) {
                    target.setAttribute(tag, value)
                }
            }
        } catch (e: Exception) {
            Log.d("ExifCopy", "复制额外标签失败: ${e.message}")
        }
    }
    
    private fun createFramedImage(originalBitmap: Bitmap): Bitmap {
        val cameraInfo = binding.etCameraModel.text.toString()
        val photoInfo = binding.etPhotoInfo.text.toString()
        
        // 计算不同边框宽度
        val sideWidth = originalBitmap.width * 0.01f // 左右边框较窄
        val topWidth = originalBitmap.width * 0.01f // 顶部边框较窄
        val bottomWidth = originalBitmap.width * 0.12f // 底部边框较宽
        
        // 计算输出图片的总尺寸
        val totalWidth = originalBitmap.width + 2 * sideWidth.toInt()
        val totalHeight = originalBitmap.height + topWidth.toInt() + bottomWidth.toInt()
        
        // 创建新的位图
        val outputBitmap = Bitmap.createBitmap(totalWidth.toInt(), totalHeight.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        
        // 计算圆角半径
        val cornerRadius = if (isCornerEnabled && cornerRadiusPercent > 0) {
            originalBitmap.width * (cornerRadiusPercent / 100f) * 0.03f
        } else {
            0f
        }
        
        // 绘制带圆角的边框（相框背景）
        val backgroundPaint = Paint().apply {
            color = selectedFrameColor
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        // 创建一个矩形，用于绘制整个相框
        val rectF = RectF(0f, 0f, totalWidth.toFloat(), totalHeight.toFloat())
        
        // 如果启用了圆角，绘制带圆角的矩形；否则绘制普通矩形
        if (cornerRadius > 0) {
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, backgroundPaint)
        } else {
            canvas.drawRect(rectF, backgroundPaint)
        }
        
        // 处理照片圆角
        if (isCornerEnabled && cornerRadiusPercent > 0) {
            // 创建一个带圆角的位图
            val roundedBitmap = getRoundedCornerBitmap(originalBitmap, cornerRadius)
            
            // 绘制圆角图片
            canvas.drawBitmap(
                roundedBitmap,
                sideWidth,
                topWidth,
                null
            )
        } else {
            // 绘制原始图片（无圆角）
            canvas.drawBitmap(
                originalBitmap,
                sideWidth,
                topWidth,
                null
            )
        }
        
        // 生成文字颜色
        val textColor = getComplementaryTextColor(selectedFrameColor)
        
        // 创建文本画笔
        val textPaint = Paint().apply {
            color = textColor
            // 使用系统默认字体的粗体变体
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            isAntiAlias = true // 启用抗锯齿，使文字更平滑
        }

        // 计算智能文字大小
        val textSettings = calculateOptimalTextSize(
            cameraInfo, 
            photoInfo, 
            totalWidth.toFloat(), 
            bottomWidth, 
            sideWidth
        )
        
        textPaint.textSize = textSettings.textSize
        
        // 计算字体指标，用于垂直居中
        val fontMetrics = textPaint.fontMetrics
        // 文字基线到字体顶部的距离
        val textHeight = fontMetrics.bottom - fontMetrics.top
        
        // 计算文字垂直居中位置
        // 图片底部 + 底部边框高度的一半 + 文字基线偏移(文字高度一半 - 基线到底部距离)
        val textY = (originalBitmap.height + topWidth.toInt()) + 
                    (bottomWidth / 2) + 
                    ((textHeight / 2) - fontMetrics.bottom)
        
        // 左下角绘制相机信息 - 垂直居中
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            textSettings.leftText,
            sideWidth * 3.0f, // 增加左侧边距
            textY,
            textPaint
        )
        
        // 右下角绘制照片信息 - 垂直居中
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            textSettings.rightText,
            totalWidth - sideWidth * 3.0f, // 增加右侧边距
            textY,
            textPaint
        )
        
        return outputBitmap
    }
    
    /**
     * 创建带圆角的位图
     */
    private fun getRoundedCornerBitmap(bitmap: Bitmap, radius: Float): Bitmap {
        val output = Bitmap.createBitmap(
            bitmap.width,
            bitmap.height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(output)
        
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
        }
        
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        val rectF = RectF(rect)
        
        canvas.drawRoundRect(rectF, radius, radius, paint)
        
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        
        return output
    }
    
    /**
     * 获取与背景色相协调的文字颜色
     * 如果背景是深色，返回相近的浅色
     * 如果背景是浅色，返回相近的深色
     */
    private fun getComplementaryTextColor(backgroundColor: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(backgroundColor, hsl)
        
        // 判断背景是浅色还是深色
        val isBackgroundDark = hsl[2] < 0.5f
        
        // 保持色相和饱和度，调整亮度
        if (isBackgroundDark) {
            // 如果背景是深色，生成一个相近的浅色
            hsl[2] = 0.85f  // 高亮度
        } else {
            // 如果背景是浅色，生成一个相近的深色
            hsl[2] = 0.25f  // 低亮度
        }
        
        return ColorUtils.HSLToColor(hsl)
    }
    
    /**
     * 切换原始图片和预览模式
     */
    private fun togglePreviewMode() {
        isPreviewMode = !isPreviewMode
        
        if (isPreviewMode) {
            // 切换到预览模式
            binding.tvPreviewLabel.text = "实时预览 (点击切换)"
            updatePreview()
        } else {
            // 切换到原始图片
            binding.tvPreviewLabel.text = "原始图片 (点击查看预览)"
            originalBitmap?.let {
                binding.imagePreview.setImageBitmap(it)
            }
        }
    }

    /**
     * 更新实时预览图像
     */
    private fun updatePreview() {
        originalBitmap?.let { bitmap ->
            try {
                // 计算预览图片尺寸，保持比例
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels - (32 * displayMetrics.density).toInt() // 减去边距
                
                // 根据屏幕宽度和原始比例计算高度
                val ratio = bitmap.height.toFloat() / bitmap.width.toFloat()
                val previewWidth = screenWidth
                val previewHeight = (screenWidth * ratio).toInt()
                
                // 限制最大高度
                val maxHeight = (400 * displayMetrics.density).toInt()
                val finalHeight = minOf(previewHeight, maxHeight)
                val finalWidth = if (previewHeight > maxHeight) {
                    (maxHeight / ratio).toInt()
                } else {
                    previewWidth
                }
                
                // 创建预览图片
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, finalWidth, finalHeight, true)
                
                // 生成预览相框图像
                val framedBitmap = createFramedImagePreview(scaledBitmap)
                
                // 更新预览
                binding.imagePreview.setImageBitmap(framedBitmap)
            } catch (e: Exception) {
                // 如果预览生成失败，使用原始图像
                Toast.makeText(this, "预览生成失败: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.imagePreview.setImageBitmap(bitmap)
            }
        }
    }
    
    /**
     * 为预览创建相框图像，与最终输出版本略有不同
     */
    private fun createFramedImagePreview(originalBitmap: Bitmap): Bitmap {
        val cameraInfo = binding.etCameraModel.text.toString()
        val photoInfo = binding.etPhotoInfo.text.toString()
        
        // 使用与最终生成图片相同的边框宽度
        val sideWidth = originalBitmap.width * 0.01f // 左右边框较窄
        val topWidth = originalBitmap.width * 0.01f // 顶部边框较窄
        val bottomWidth = originalBitmap.width * 0.12f // 底部边框较宽
        
        // 计算输出图片的总尺寸
        val totalWidth = originalBitmap.width + 2 * sideWidth.toInt()
        val totalHeight = originalBitmap.height + topWidth.toInt() + bottomWidth.toInt()
        
        // 创建新的位图
        val outputBitmap = Bitmap.createBitmap(totalWidth.toInt(), totalHeight.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        
        // 计算圆角半径
        val cornerRadius = if (isCornerEnabled && cornerRadiusPercent > 0) {
            originalBitmap.width * (cornerRadiusPercent / 100f) * 0.03f
        } else {
            0f
        }
        
        // 绘制带圆角的边框（相框背景）
        val backgroundPaint = Paint().apply {
            color = selectedFrameColor
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        // 创建一个矩形，用于绘制整个相框
        val rectF = RectF(0f, 0f, totalWidth.toFloat(), totalHeight.toFloat())
        
        // 如果启用了圆角，绘制带圆角的矩形；否则绘制普通矩形
        if (cornerRadius > 0) {
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, backgroundPaint)
        } else {
            canvas.drawRect(rectF, backgroundPaint)
        }
        
        // 处理照片圆角
        if (isCornerEnabled && cornerRadiusPercent > 0) {
            // 创建一个带圆角的位图
            val roundedBitmap = getRoundedCornerBitmap(originalBitmap, cornerRadius)
            
            // 绘制圆角图片
            canvas.drawBitmap(
                roundedBitmap,
                sideWidth,
                topWidth,
                null
            )
        } else {
            // 绘制原始图片（无圆角）
            canvas.drawBitmap(
                originalBitmap,
                sideWidth,
                topWidth,
                null
            )
        }
        
        // 生成文字颜色
        val textColor = getComplementaryTextColor(selectedFrameColor)
        
        // 创建文本画笔
        val textPaint = Paint().apply {
            color = textColor
            // 使用系统默认字体的粗体变体
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            isAntiAlias = true // 启用抗锯齿，使文字更平滑
        }
        
        // 使用与最终图像相同的文字大小计算方法
        val textSettings = calculateOptimalTextSize(
            cameraInfo, 
            photoInfo, 
            totalWidth.toFloat(), 
            bottomWidth, 
            sideWidth
        )
        
        textPaint.textSize = textSettings.textSize
        
        // 计算字体指标，用于垂直居中
        val fontMetrics = textPaint.fontMetrics
        // 文字基线到字体顶部的距离
        val textHeight = fontMetrics.bottom - fontMetrics.top
        
        // 计算文字垂直居中位置
        // 图片底部 + 底部边框高度的一半 + 文字基线偏移(文字高度一半 - 基线到底部距离)
        val textY = (originalBitmap.height + topWidth.toInt()) + 
                    (bottomWidth / 2) + 
                    ((textHeight / 2) - fontMetrics.bottom)
        
        // 左下角绘制相机信息 - 垂直居中
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            textSettings.leftText,
            sideWidth * 3.0f, // 增加左侧边距
            textY,
            textPaint
        )
        
        // 右下角绘制照片信息 - 垂直居中
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            textSettings.rightText,
            totalWidth - sideWidth * 3.0f, // 增加右侧边距
            textY,
            textPaint
        )
        
        return outputBitmap
    }
    
    /**
     * 根据文本长度和图片宽度计算最佳文字大小和处理后的文本
     */
    private fun calculateOptimalTextSize(
        leftText: String,
        rightText: String,
        totalWidth: Float,
        bottomHeight: Float,
        sideWidth: Float
    ): TextSettings {
        // 临时画笔用于文本测量
        val tempPaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        
        // 计算基础字体大小因素
        val totalChars = leftText.length + rightText.length
        val availableWidth = totalWidth - (sideWidth * 6.0f) // 考虑更大的两侧边距
        
        // 字符密度因子 - 字符数与可用宽度的比率
        val charDensity = totalChars / availableWidth
        
        // 基于图片宽度和字符密度动态计算初始字体大小
        var baseTextSize = bottomHeight * 0.4f // 基础大小
        
        // 考虑字符密度调整大小
        if (charDensity > 0.1f) {
            // 字符密度较高，适当缩小字体
            baseTextSize *= (1f - (charDensity * 2f)).coerceIn(0.35f, 0.9f)
        }
        
        // 确保字体大小下限
        val minTextSize = (bottomHeight * 0.2f).coerceAtLeast(12f)
        baseTextSize = baseTextSize.coerceAtLeast(minTextSize)
        
        // 设置测量字体大小
        tempPaint.textSize = baseTextSize
        
        // 计算两侧文本最大可用宽度（考虑中间间隙）
        val sideMaxWidth = (availableWidth * 0.45f).coerceAtMost(totalWidth * 0.4f)
        
        // 测量文本宽度
        val leftWidth = tempPaint.measureText(leftText)
        val rightWidth = tempPaint.measureText(rightText)
        
        // 如果任一文本超出最大宽度，进一步调整字体大小或截断文本
        var finalLeftText = leftText
        var finalRightText = rightText
        var finalTextSize = baseTextSize
        
        if (leftWidth > sideMaxWidth || rightWidth > sideMaxWidth) {
            // 先尝试适度缩小字体
            val leftScale = if (leftWidth > 0) sideMaxWidth / leftWidth else 1f
            val rightScale = if (rightWidth > 0) sideMaxWidth / rightWidth else 1f
            val scale = minOf(leftScale, rightScale)
            
            // 如果缩放不会导致字体过小，则缩放字体
            if (baseTextSize * scale >= minTextSize) {
                finalTextSize = baseTextSize * scale
            } else {
                // 字体已经达到最小，需要截断文本
                finalTextSize = minTextSize
                tempPaint.textSize = finalTextSize
                
                // 处理左侧文本
                if (tempPaint.measureText(leftText) > sideMaxWidth && leftText.length > 5) {
                    val ellipsis = "..."
                    var shortened = leftText
                    while (tempPaint.measureText(shortened + ellipsis) > sideMaxWidth && shortened.length > 3) {
                        shortened = shortened.substring(0, shortened.length - 1)
                    }
                    finalLeftText = shortened + ellipsis
                }
                
                // 处理右侧文本
                if (tempPaint.measureText(rightText) > sideMaxWidth && rightText.length > 5) {
                    val ellipsis = "..."
                    var shortened = rightText
                    while (tempPaint.measureText(ellipsis + shortened) > sideMaxWidth && shortened.length > 3) {
                        shortened = shortened.substring(1)
                    }
                    finalRightText = ellipsis + shortened
                }
            }
        }
        
        return TextSettings(finalTextSize, finalLeftText, finalRightText)
    }
    
    /**
     * 文字设置数据类
     */
    private data class TextSettings(
        val textSize: Float,
        val leftText: String,
        val rightText: String
    )
    
    private fun max(a: Float, b: Float): Float {
        return if (a > b) a else b
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // 加载菜单资源
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun showAboutDialog() {
        val aboutContent = """
            <p>Directed by X@<a href="https://x.com/SeimoDev">SeimoDev</a></p>
            <p>Dev: Cursor &amp; Claude 3.7</p>
            <p>图标: Gemini 2.0 exp</p>
            <p><i><u>我们都是半山腰上的人</u></i></p>
            <p><a href="https://github.com/SeimoDev/ColorfulPicture">Github 仓库</a></p><br/>
            <p>特别鸣谢: X@<a href="https://x.com/Yayoi_no_yume">◂Ⓘ▸YAYOI の 夢</a></p>
        """.trimIndent()
        
        // 创建对话框
        val dialog = AlertDialog.Builder(this)
            .setTitle("关于")
            .setMessage(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(aboutContent, Html.FROM_HTML_MODE_COMPACT)
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(aboutContent)
            })
            .setPositiveButton("确定", null)
            .create()
        
        dialog.show()
        
        // 使链接可点击
        val messageView = dialog.findViewById<TextView>(android.R.id.message)
        messageView?.movementMethod = LinkMovementMethod.getInstance()
    }
}