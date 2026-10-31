package com.degradatione.tdmobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Узлы как в TouchDesigner: один вход/выход, у Blend два входа.
 * Каждый узел — один проход фрагментного шейдера по FBO.
 */
enum class NodeKind(
    val label: String,
    val inputs: Int,
    val p1: String,
    val p2: String,
    val accent: Long,
) {
    GRADIENT("Gradient", 0, "hue", "speed", 0xFF7B61FF),
    NOISE("Noise", 0, "scale", "speed", 0xFF7B61FF),
    BLUR("Blur", 1, "radius", "—", 0xFF7B61FF),
    KALEIDO("Kaleido", 1, "segments", "rotate", 0xFF7B61FF),
    TWIST("Twist", 1, "amount", "speed", 0xFF7B61FF),
    MIRROR("Mirror", 1, "tiles", "offset", 0xFF7B61FF),
    COLORIZE("Colorize", 1, "hue", "saturation", 0xFF7B61FF),
    FEEDBACK("Feedback", 1, "decay", "zoom", 0xFF00C8A0),
    BLEND("Blend", 2, "mix", "mode", 0xFF00C8A0),
    OUT("Output", 1, "—", "—", 0xFFFFB300),
}

class Node(
    val id: Int,
    val kind: NodeKind,
    var x: Float,
    var y: Float,
    var p1: Float,
    var p2: Float,
    var name: String = kind.label,
) {
    /** индекс входа → id узла-источника */
    val inputs: MutableMap<Int, Int> = mutableMapOf()
}

class Patch {
    var nodesState by mutableIntStateOf(0)
    val nodes = mutableListOf<Node>()
    private var seq = 1

    fun nextId(): Int = seq++

    fun add(kind: NodeKind, x: Float, y: Float): Node {
        val n = Node(
            id = nextId(),
            kind = kind,
            x = x,
            y = y,
            p1 = when (kind) {
                NodeKind.GRADIENT -> 0.6f
                NodeKind.NOISE -> 4f
                NodeKind.BLUR -> 2f
                NodeKind.KALEIDO -> 6f
                NodeKind.TWIST -> 3f
                NodeKind.MIRROR -> 2f
                NodeKind.COLORIZE -> 0.1f
                NodeKind.FEEDBACK -> 0.92f
                NodeKind.BLEND -> 0.5f
                NodeKind.OUT -> 1f
            },
            p2 = when (kind) {
                NodeKind.GRADIENT -> 0.4f
                NodeKind.NOISE -> 0.3f
                NodeKind.BLUR -> 0f
                NodeKind.KALEIDO -> 0f
                NodeKind.TWIST -> 0.5f
                NodeKind.MIRROR -> 0.2f
                NodeKind.COLORIZE -> 1f
                NodeKind.FEEDBACK -> 1f
                NodeKind.BLEND -> 0f
                NodeKind.OUT -> 0f
            },
            name = kind.label + " " + nextId(),
        )
        nodes.add(n)
        bump()
        return n
    }

    fun remove(n: Node) {
        nodes.remove(n)
        for (other in nodes) {
            other.inputs.entries.removeIf { it.value == n.id }
        }
        bump()
    }

    fun connect(sourceId: Int, targetId: Int, inputIndex: Int = 0) {
        val target = nodes.firstOrNull { it.id == targetId } ?: return
        if (sourceId == targetId) return
        if (inputIndex >= target.kind.inputs) return
        target.inputs[inputIndex] = sourceId
        bump()
    }

    fun disconnect(targetId: Int, inputIndex: Int) {
        nodes.firstOrNull { it.id == targetId }?.inputs?.remove(inputIndex)
        bump()
    }

    fun bump() {
        nodesState++
    }

    fun outputNode(): Node? = nodes.firstOrNull { it.kind == NodeKind.OUT }

    /** Раскладывает сеть в вертикальный столбец — под узкий экран телефона. */
    fun layoutVertical() {
        val order = evalOrder()
        val rest = nodes.filter { n -> order.none { it.id == n.id } }
        var y = 30f
        for (n in order + rest) {
            n.x = 24f
            n.y = y
            y += 168f
        }
        bump()
    }

    /** Порядок вычисления: от выхода назад по связям. */
    fun evalOrder(): List<Node> {
        val out = outputNode() ?: return emptyList()
        val order = mutableListOf<Node>()
        val seen = mutableSetOf<Int>()
        fun visit(n: Node) {
            if (!seen.add(n.id)) return
            for (i in 0 until n.kind.inputs) {
                n.inputs[i]?.let { src -> nodes.firstOrNull { it.id == src }?.let { visit(it) } }
            }
            order.add(n)
        }
        visit(out)
        return order
    }

    fun toJson(): String {
        val arr = JSONArray()
        for (n in nodes) {
            val ins = JSONObject()
            n.inputs.forEach { (k, v) -> ins.put(k.toString(), v) }
            arr.put(
                JSONObject()
                    .put("id", n.id)
                    .put("kind", n.kind.name)
                    .put("x", n.x)
                    .put("y", n.y)
                    .put("p1", n.p1)
                    .put("p2", n.p2)
                    .put("name", n.name)
                    .put("inputs", ins)
            )
        }
        return JSONObject().put("seq", seq).put("nodes", arr).toString()
    }

    fun loadJson(json: String): Boolean = runCatching {
        val o = JSONObject(json)
        val arr = o.getJSONArray("nodes")
        val fresh = mutableListOf<Node>()
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val kind = NodeKind.valueOf(j.getString("kind"))
            val n = Node(
                id = j.getInt("id"),
                kind = kind,
                x = j.getDouble("x").toFloat(),
                y = j.getDouble("y").toFloat(),
                p1 = j.getDouble("p1").toFloat(),
                p2 = j.getDouble("p2").toFloat(),
                name = j.optString("name", kind.label),
            )
            val ins = j.optJSONObject("inputs")
            ins?.keys()?.forEach { k -> n.inputs[k.toInt()] = ins.getInt(k) }
            fresh.add(n)
        }
        nodes.clear()
        nodes.addAll(fresh)
        seq = o.optInt("seq", (nodes.maxOfOrNull { it.id } ?: 0) + 1)
        bump()
        true
    }.getOrDefault(false)

    companion object {
        /** Готовые сети: плазма, вихрь, грязное зеркало. */
        fun presetPlasma(): Patch = Patch().apply {
            val grad = add(NodeKind.GRADIENT, 40f, 60f)
            val noise = add(NodeKind.NOISE, 40f, 240f)
            val blend = add(NodeKind.BLEND, 300f, 140f)
            val kaleido = add(NodeKind.KALEIDO, 520f, 140f)
            val out = add(NodeKind.OUT, 740f, 140f)
            blend.inputs[0] = grad.id
            blend.inputs[1] = noise.id
            blend.p1 = 0.55f
            kaleido.inputs[0] = blend.id
            kaleido.p1 = 8f
            out.inputs[0] = kaleido.id
            bump()
        }

        fun presetVortex(): Patch = Patch().apply {
            val noise = add(NodeKind.NOISE, 40f, 80f)
            val twist = add(NodeKind.TWIST, 280f, 80f)
            val fb = add(NodeKind.FEEDBACK, 500f, 80f)
            val color = add(NodeKind.COLORIZE, 720f, 80f)
            val out = add(NodeKind.OUT, 940f, 80f)
            twist.inputs[0] = noise.id
            twist.p1 = 4f
            fb.inputs[0] = twist.id
            fb.p1 = 0.94f
            color.inputs[0] = fb.id
            color.p1 = 0.55f
            out.inputs[0] = color.id
            bump()
        }

        fun presetMirror(): Patch = Patch().apply {
            val grad = add(NodeKind.GRADIENT, 40f, 90f)
            val mirror = add(NodeKind.MIRROR, 280f, 90f)
            val blur = add(NodeKind.BLUR, 500f, 90f)
            val out = add(NodeKind.OUT, 720f, 90f)
            mirror.inputs[0] = grad.id
            mirror.p1 = 3f
            blur.inputs[0] = mirror.id
            blur.p1 = 3f
            out.inputs[0] = blur.id
            bump()
        }
    }
}