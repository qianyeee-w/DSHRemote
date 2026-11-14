package io.github.qianyeeew.dshremote.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.OutgoingImage
import io.github.qianyeeew.dshremote.data.image.ImagePreparer
import io.github.qianyeeew.dshremote.data.image.ImageRef
import io.github.qianyeeew.dshremote.data.image.ImageSizing
import io.github.qianyeeew.dshremote.data.image.ImageStore
import io.github.qianyeeew.dshremote.ui.DesignTheme

// ---------------------------------------------------------------------------
// 图片附件（落地方案 C2）：输入框的加号和缩略图（设计规格 6.3、7.6），气泡里的图片，点了看大图。
// 缩略图加载前是图片占位色 #ECEBE6。
// ---------------------------------------------------------------------------

/** 读图片用的缓存。界面测试里没有时只显示占位色。 */
val LocalImageStore = staticCompositionLocalOf<ImageStore?> { null }

/** 压缩用户选的图片。界面测试里没有时加不上图片。 */
val LocalImagePreparer = staticCompositionLocalOf<ImagePreparer?> { null }

/** 一张图片的读取状态。 */
private sealed interface ImageLoad {
    data object Loading : ImageLoad

    data class Loaded(
        val bitmap: Bitmap,
    ) : ImageLoad

    data object Failed : ImageLoad
}

/** 读一张图片，解码到长边大约 [maxSide] 像素。[attempt] 变了就重读（读不到时点一下重试）。 */
@Composable
private fun rememberImage(
    ref: ImageRef,
    maxSide: Int,
    attempt: Int = 0,
): ImageLoad {
    val store = LocalImageStore.current
    val load by produceState<ImageLoad>(ImageLoad.Loading, ref, maxSide, attempt, store) {
        value = ImageLoad.Loading
        val bitmap = store?.load(ref, maxSide)
        value =
            if (bitmap != null) {
                ImageLoad.Loaded(bitmap)
            } else if (store == null) {
                ImageLoad.Loading
            } else {
                ImageLoad.Failed
            }
    }
    return load
}

/**
 * 一块显示图片的区域：加载前是占位色，读不到时写「读不到图片」，点一下重试；读到了按 [scale] 填进去。
 */
@Composable
private fun ImageBox(
    ref: ImageRef,
    size: Pair<Dp, Dp>,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    scale: ContentScale = ContentScale.Crop,
    onClick: (() -> Unit)? = null,
) {
    val colors = DesignTheme.colors
    val density = LocalDensity.current
    val maxSide = with(density) { maxOf(size.first, size.second).roundToPx() }
    var attempt by remember { mutableIntStateOf(0) }
    val load = rememberImage(ref, maxSide, attempt)
    val label = stringResource(R.string.image_in_message, ref.width, ref.height)
    Box(
        modifier
            .size(size.first, size.second)
            .clip(shape)
            .background(colors.imagePlaceholder)
            .let { m ->
                when {
                    load is ImageLoad.Failed -> m.clickable(role = Role.Button) { attempt++ }
                    onClick != null -> m.clickable(role = Role.Image, onClickLabel = stringResource(R.string.image_open), onClick = onClick)
                    else -> m
                }
            }.semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when (load) {
            is ImageLoad.Loaded -> Image(load.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = scale)
            ImageLoad.Failed ->
                Text(
                    stringResource(R.string.image_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(4.dp),
                )
            ImageLoad.Loading -> Unit
        }
    }
}

// ---------------------------------------------------------------- 输入框

/**
 * 输入框里的加号（设计规格 6.3）：点按区域 44，图标 20。现在不能加图片时画成不可用，但点了说明原因（[onUnavailable]）。
 */
@Composable
fun AddImageButton(
    available: Boolean,
    onAdd: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DesignTheme.colors
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = if (available) onAdd else onUnavailable)
            .testTag("chat-add-image"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_dsh_plus),
            stringResource(if (available) R.string.image_add else R.string.image_add_unavailable),
            Modifier.size(20.dp).alpha(if (available) 1f else DISABLED_ALPHA),
            tint = colors.textSecondary,
        )
    }
}

/**
 * 输入框里已经加上的图片（设计规格 7.6）：56 见方、圆角 10，右上角一个移除按钮（黑色小圆、白色叉，点按区域 44）。
 * 还在压缩的图片先画成占位色加一个转圈。
 */
@Composable
fun ComposerImageStrip(
    images: List<OutgoingImage>,
    preparing: Int,
    onRemove: (key: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty() && preparing == 0) return
    val colors = DesignTheme.colors
    val shape = RoundedCornerShape(10.dp)
    // 移除按钮伸出缩略图外面，上面和右边留出地方
    Row(modifier.padding(top = 10.dp).testTag("chat-images"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (image in images) {
            Box(Modifier.testTag("chat-image")) {
                ImageBox(ImageRef.Local(image), 56.dp to 56.dp, shape)
                val remove = stringResource(R.string.image_remove)
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 16.dp, y = (-16).dp)
                        .size(44.dp)
                        .clickable(role = Role.Button) { onRemove(image.key) }
                        .semantics { contentDescription = remove }
                        .testTag("chat-image-remove"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(colors.text), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_dsh_close), null, Modifier.size(9.dp), tint = colors.panel)
                    }
                }
            }
        }
        repeat(preparing) {
            val label = stringResource(R.string.image_preparing)
            Box(
                Modifier
                    .size(56.dp)
                    .clip(shape)
                    .background(colors.imagePlaceholder)
                    .semantics { contentDescription = label }
                    .testTag("chat-image-preparing"),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    color = colors.textSecondary,
                    trackColor = colors.spinnerTrack,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 气泡

/**
 * 用户消息里的图片，靠右，放在文字气泡上面。一张时按比例显示（最宽 240、最高 280）；多张时两列 118 见方。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BubbleImages(
    images: List<ImageRef>,
    onOpen: (ImageRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    val shape = RoundedCornerShape(14.dp)
    if (images.size == 1) {
        val image = images.single()
        val (w, h) = ImageSizing.bubbleSize(image.width, image.height, maxWidth = 240f, maxHeight = 280f, minSide = 72f)
        ImageBox(image, w.dp to h.dp, shape, modifier.testTag("bubble-image"), onClick = { onOpen(image) })
        return
    }
    FlowRow(
        modifier.testTag("bubble-images"),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = 2,
    ) {
        for (image in images) ImageBox(image, 118.dp to 118.dp, shape, Modifier.testTag("bubble-image"), onClick = { onOpen(image) })
    }
}

// ---------------------------------------------------------------- 大图

/** 全屏看一张图片：适应屏幕，双指缩放和拖动，双击放大或复原。返回键、右上角的叉关闭。 */
@Composable
fun ImageViewer(
    image: ImageRef,
    onDismiss: () -> Unit,
) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("image-viewer")) {
            val density = LocalDensity.current
            val maxSide = with(density) { maxOf(maxWidth, maxHeight).roundToPx() }
            var zoom by remember { mutableFloatStateOf(1f) }
            var pan by remember { mutableStateOf(Offset.Zero) }
            val load = rememberImage(image, maxSide)
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            zoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
                            pan = if (zoom == 1f) Offset.Zero else pan + panChange
                        }
                    }.pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            zoom = if (zoom > 1f) 1f else DOUBLE_TAP_ZOOM
                            pan = Offset.Zero
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                when (load) {
                    is ImageLoad.Loaded ->
                        Image(
                            load.bitmap.asImageBitmap(),
                            stringResource(R.string.image_in_message, image.width, image.height),
                            Modifier.fillMaxSize().graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                            },
                            contentScale = ContentScale.Fit,
                        )
                    ImageLoad.Failed -> Text(stringResource(R.string.image_failed), color = Color.White)
                    ImageLoad.Loading -> CircularProgressIndicator(color = Color.White)
                }
            }
            Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .testTag("image-viewer-close"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_dsh_close),
                        stringResource(R.string.image_viewer_close),
                        Modifier.size(16.dp),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.4f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
