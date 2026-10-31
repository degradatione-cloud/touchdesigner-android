package com.degradatione.tdmobile

import android.content.ContentValues
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

object Td {
    val bg = Color(0xFF0E0E12)
    val panel = Color(0xFF17171D)
    val card = Color(0xFF1F1F27)
    val border = Color(0xFF2E2E3A)
    val text = Color(0xFFEDEDF2)
    val dim = Color(0xFF9A9AA8)
    val faint = Color(0xFF63636F)
    val top = Color(0xFF7B61FF)
    val chop = Color(0xFF00C8A0)
    val out = Color(0xFFFFB300)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val engine = GlEngine()
        val patch = Patch.presetPlasma()
        patch.layoutVertical()
        engine.patch = patch
        setContent { TdTheme { TdApp(engine, patch) } }
    }
}

@Composable
fun TdTheme(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Td.top, background = Td.bg, surface = Td.panel,
            onSurface = Td.text, outline = Td.border,
        ),
        content = content,
    )
}

@Composable
fun TdApp(engine: GlEngine, patch: Patch) {
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf(true) }
    var fps by remember { mutableFloatStateOf(0f) }
    var addOpen by remember { mutableStateOf(false) }
    var presetOpen by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf<Int?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            fps = engine.fps
            kotlinx.coroutines.delay(400)
        }
    }

    fun fitIntoColumn() {
        patch.layoutVertical()
        toast = "Сеть выстроена в столбец"
    }

    Column(Modifier.fillMaxSize().background(Td.bg).systemBarsPadding().imePadding()) {
        // ── превью ────────────────────────────────────────────────────────
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color.Black)
        ) {
            AndroidView(
                factory = { c ->
                    GLSurfaceView(c).apply {
                        setEGLContextClientVersion(2)
                        setRenderer(engine)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Td.panel.copy(alpha = 0.82f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(if (playing) Td.chop else Td.faint, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${fps.roundToInt()} fps · ${patch.nodes.size} узлов",
                    color = Td.dim, fontSize = 10.sp,
                )
            }
            Row(
                Modifier.align(Alignment.BottomEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RoundBtn(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Пауза") {
                    playing = !playing
                    engine.playing = playing
                }
                RoundBtn(Icons.Rounded.CameraAlt, "Снимок") {
                    engine.requestSnapshot { bmp ->
                        if (bmp == null) {
                            toast = "Снимок не вышел"
                        } else {
                            runCatching {
                                val name = "TD_${System.currentTimeMillis()}.png"
                                val values = ContentValues().apply {
                                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TouchDesigner")
                                }
                                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                    ?: error("MediaStore отказал")
                                ctx.contentResolver.openOutputStream(uri)!!.use {
                                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                }
                                toast = "Снимок → Pictures/TouchDesigner"
                            }.onFailure { toast = "Не сохранилось: ${it.message}" }
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = toast != null,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(140)),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            ) {
                Text(
                    toast.orEmpty(),
                    color = Td.text, fontSize = 10.sp,
                    modifier = Modifier.background(Td.panel.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        // ── панель графа ──────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().background(Td.panel).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Chip("Пресеты") { presetOpen = true }
                DropdownMenu(presetOpen, { presetOpen = false }, containerColor = Td.card) {
                    for ((name, builder) in listOf(
                        "Плазма" to { Patch.presetPlasma() },
                        "Вихрь" to { Patch.presetVortex() },
                        "Зеркало" to { Patch.presetMirror() },
                    )) {
                        DropdownMenuItem(
                            text = { Text(name, color = Td.text, fontSize = 13.sp) },
                            onClick = {
                                presetOpen = false
                                val fresh = builder()
                                fresh.layoutVertical()
                                patch.nodes.clear()
                                patch.nodes.addAll(fresh.nodes)
                                patch.bump()
                                toast = "Пресет: $name"
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Chip("Столбец") { fitIntoColumn() }
            Spacer(Modifier.width(6.dp))
            Chip("Очистить") {
                patch.nodes.clear()
                patch.bump()
                toast = "Сеть очищена"
            }
            Spacer(Modifier.weight(1f))
            Box {
                RoundBtn(Icons.Rounded.Add, "Добавить узел") { addOpen = true }
                DropdownMenu(addOpen, { addOpen = false }, containerColor = Td.card) {
                    for (kind in NodeKind.entries) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(7.dp).background(Color(kind.accent), CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Text(kind.label, color = Td.text, fontSize = 13.sp)
                                }
                            },
                            onClick = {
                                addOpen = false
                                val y = (patch.nodes.maxOfOrNull { it.y } ?: 40f) + 150f
                                patch.add(kind, 30f, y)
                                toast = "Добавлен ${kind.label}"
                            },
                        )
                    }
                }
            }
        }

        PatchCanvas(
            patch = patch,
            linking = linking,
            onLinkChange = { linking = it },
            modifier = Modifier.fillMaxWidth().weight(1.15f),
        )
    }
}

@Composable
fun RoundBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    Box(
        Modifier.size(34.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .background(Td.card, CircleShape)
            .border(1.dp, Td.border, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Td.text, modifier = Modifier.size(16.dp)) }
}

@Composable
fun Chip(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "chip")
    Box(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .background(Td.card, RoundedCornerShape(10.dp))
            .border(1.dp, Td.border, RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) { Text(text, color = Td.dim, fontSize = 10.5.sp) }
}

@Composable
fun PatchCanvas(
    patch: Patch,
    linking: Int?,
    onLinkChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pan by remember { mutableStateOf(Offset(0f, -20f)) }
    val width = 150.dp
    val density = LocalDensity.current.density
    val cardPx = width.value * density
    val nodes = patch.nodes
    val state = patch.nodesState

    Box(
        modifier
            .background(Td.bg)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    pan += drag
                }
            }
    ) {
        // сетка
        Canvas(Modifier.fillMaxSize()) {
            val step = 24f
            var x = (pan.x % step)
            while (x < size.width) {
                drawLine(Td.border.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height), 1f)
                x += step
            }
            var y = (pan.y % step)
            while (y < size.height) {
                drawLine(Td.border.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), 1f)
                y += step
            }
        }

        // связи
        Canvas(Modifier.fillMaxSize()) {
            for (n in nodes) {
                for ((idx, srcId) in n.inputs) {
                    val src = nodes.firstOrNull { it.id == srcId } ?: continue
                    val from = Offset(src.x + cardPx, src.y + 44f) + pan
                    val to = Offset(n.x + pan.x + 6f, n.y + pan.y + 44f + idx * 14f)
                    val mid = (from.y + to.y) / 2f
                    val p = Path().apply {
                        moveTo(from.x, from.y)
                        cubicTo(from.x + 40f, mid, to.x - 40f, mid, to.x, to.y)
                    }
                    drawPath(p, Color(src.kind.accent).copy(alpha = 0.75f), style = Stroke(width = 3f))
                }
            }
        }

        // узлы
        for (n in nodes) {
            key(n.id) {
                NodeCard(
                    node = n,
                    patch = patch,
                    linking = linking,
                    onLinkChange = onLinkChange,
                    modifier = Modifier
                        .offset { IntOffset((n.x + pan.x).roundToInt(), (n.y + pan.y).roundToInt()) }
                        .width(width),
                )
            }
        }

        if (linking != null) {
            Text(
                "Тапни вход другого узла, чтобы соединить",
                color = Td.text, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                    .background(Td.panel, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
fun NodeCard(
    node: Node,
    patch: Patch,
    linking: Int?,
    onLinkChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(node.kind.accent)
    Column(
        modifier
            .background(Td.card, RoundedCornerShape(12.dp))
            .border(1.dp, if (linking == node.id) accent else Td.border, RoundedCornerShape(12.dp))
            .padding(bottom = 6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .pointerInput(node.id) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        node.x += drag.x
                        node.y += drag.y
                        patch.bump()
                    }
                }
                .background(accent.copy(alpha = 0.18f), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(accent, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                node.name, color = Td.text, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(20.dp).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { patch.remove(node) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, "Удалить", tint = Td.faint, modifier = Modifier.size(12.dp)) }
        }

        if (node.kind.inputs > 0) {
            Row(Modifier.padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until node.kind.inputs) {
                    val connected = node.inputs[i] != null
                    Box(
                        Modifier.size(18.dp)
                            .background(if (connected) accent.copy(alpha = 0.3f) else Td.panel, CircleShape)
                            .border(1.dp, if (connected) accent else Td.border, CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                val src = linking
                                if (src != null && src != node.id) {
                                    patch.connect(src, node.id, i)
                                    onLinkChange(null)
                                } else if (connected) {
                                    patch.disconnect(node.id, i)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", color = Td.faint, fontSize = 8.sp)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text("вход", color = Td.faint, fontSize = 8.sp)
            }
        }

        if (node.kind.p1 != "—") {
            ParamSlider(node.kind.p1, node.p1, 0f, 1f) { node.p1 = it; patch.bump() }
        }
        if (node.kind.p2 != "—") {
            ParamSlider(node.kind.p2, node.p2, 0f, 1f) { node.p2 = it; patch.bump() }
        }

        if (node.kind != NodeKind.OUT) {
            Row(Modifier.fillMaxWidth().padding(end = 6.dp), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier.size(18.dp)
                        .background(if (linking == node.id) accent else accent.copy(alpha = 0.35f), CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onLinkChange(if (linking == node.id) null else node.id) }
                )
            }
        }
    }
}

@Composable
fun ParamSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Td.faint, fontSize = 8.5.sp, modifier = Modifier.weight(1f))
            Text("%.2f".format(value), color = Td.dim, fontSize = 8.5.sp)
        }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onChange,
            valueRange = min..max,
            modifier = Modifier.height(18.dp),
            colors = SliderDefaults.colors(
                thumbColor = Td.text, activeTrackColor = Td.top, inactiveTrackColor = Td.border,
            ),
        )
    }
}