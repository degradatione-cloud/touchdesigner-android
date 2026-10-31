package com.degradatione.tdmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchTest {

    @Test
    fun presetsHaveOutputAndOrder() {
        for ((name, builder) in listOf(
            "plasma" to { Patch.presetPlasma() },
            "vortex" to { Patch.presetVortex() },
            "mirror" to { Patch.presetMirror() },
        )) {
            val p = builder()
            assertNotNull("$name: нет Output", p.outputNode())
            val order = p.evalOrder()
            assertTrue("$name: пустой порядок", order.size >= 2)
            assertEquals("$name: Output должен быть последним", NodeKind.OUT, order.last().kind)
        }
    }

    @Test
    fun connectAndDisconnect() {
        val p = Patch()
        val grad = p.add(NodeKind.GRADIENT, 0f, 0f)
        val blur = p.add(NodeKind.BLUR, 0f, 150f)
        p.connect(grad.id, blur.id, 0)
        assertEquals(grad.id, blur.inputs[0])
        p.disconnect(blur.id, 0)
        assertTrue(blur.inputs.isEmpty())
    }

    @Test
    fun selfConnectionRejected() {
        val p = Patch()
        val blur = p.add(NodeKind.BLUR, 0f, 0f)
        p.connect(blur.id, blur.id, 0)
        assertTrue(blur.inputs.isEmpty())
    }

    @Test
    fun blendAcceptsTwoInputsAndIgnoresThird() {
        val p = Patch()
        val a = p.add(NodeKind.GRADIENT, 0f, 0f)
        val b = p.add(NodeKind.NOISE, 0f, 100f)
        val blend = p.add(NodeKind.BLEND, 0f, 200f)
        p.connect(a.id, blend.id, 0)
        p.connect(b.id, blend.id, 1)
        p.connect(a.id, blend.id, 2)
        assertEquals(a.id, blend.inputs[0])
        assertEquals(b.id, blend.inputs[1])
        assertEquals(null, blend.inputs[2])
    }

    @Test
    fun jsonRoundTripKeepsParams() {
        val p = Patch.presetVortex()
        p.nodes.first().p1 = 0.42f
        val json = p.toJson()
        val restored = Patch()
        assertTrue(restored.loadJson(json))
        assertEquals(p.nodes.size, restored.nodes.size)
        assertEquals(0.42f, restored.nodes.first().p1, 0.0001f)
    }

    @Test
    fun layoutVerticalStacksNodes() {
        val p = Patch.presetPlasma()
        p.layoutVertical()
        val ys = p.nodes.map { it.y }.sorted()
        assertEquals(ys.size, ys.distinct().size)
        assertTrue(ys.zipWithNext().all { (a, b) -> b > a })
    }
}