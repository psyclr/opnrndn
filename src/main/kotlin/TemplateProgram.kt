import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.BlendMode
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.VertexElementType
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.extra.compositor.compose
import org.openrndr.extra.compositor.draw
import org.openrndr.extra.compositor.post
import org.openrndr.extra.fx.blur.FrameBlur
import org.openrndr.extra.fx.blur.GaussianBloom
import org.openrndr.extra.gui.GUI
import org.openrndr.extra.noise.poissonDiskSampling
import org.openrndr.extra.noise.simplex
import org.openrndr.extra.parameters.Description
import org.openrndr.extra.parameters.DoubleParameter
import org.openrndr.extras.camera.OrbitalCamera
import org.openrndr.extras.camera.OrbitalControls
import org.openrndr.extras.camera.applyTo
import org.openrndr.ffmpeg.VideoWriter
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.clamp
import org.openrndr.math.transforms.transform
import org.openrndr.shape.Circle
import org.openrndr.shape.Rectangle
import org.openrndr.shape.Segment3D
import openrndr.utils.BokehDepthBlur
import openrndr.utils.ColorMap
import openrndr.utils.TileGrid
import openrndr.utils.isolatedWithTarget
import openrndr.utils.rangeMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

//fun main() = application {
//    configure {
//        width = 768
//        height = 576
//    }
//
//    program {
//        val image = loadImage("data/images/pm5544.png")
//        val font = loadFont("data/fonts/default.otf", 64.0)
//
//        extend {
//            drawer.drawStyle.colorMatrix = tint(ColorRGBa.WHITE.shade(0.2))
//            drawer.image(image)
//
//            drawer.fill = ColorRGBa.PINK
//            drawer.circle(cos(seconds) * width / 2.0 + width / 2.0, sin(0.5 * seconds) * height / 2.0 + height / 2.0, 140.0)
//
//            drawer.fontMap = font
//            drawer.fill = ColorRGBa.WHITE
//            drawer.text("OPENRNDR", width / 2.0, height / 2.0)
//        }
//    }
//}
private const val TOTAL_FRAMES = 360 * 3
private const val LOOPS = 1
private const val DELAY_FRAMES = 60


fun main() = application {

    configure {
        width = 768
        height = 576
    }

    program {
        var time = 0.0
        val rng = Random(1)
        val poissonRadius = 20.0
        val tileSize = 40
        val plexusThreshold = 35.0
        val particleRadius = 1.5
        val colorMap = ColorMap(listOf("4affff", "38afff", "764efb", "f72585", "000000"))

        val grid = TileGrid<Vector3>(-width / 2.0, -height / 2.0, width, height, tileSize, tileSize)
        val waveOrigin1 = Vector3(250.0, 0.0, -200.0)

        val params =
                @Description("noise params")
                object {
                    @DoubleParameter("scale", 0.0, 1.0)
                    var scale = 0.01

                    @DoubleParameter("magnitude", 0.0, 40.0, precision = 1)
                    var magnitude = 12.0
                }

        val blurTarget = renderTarget(width, height) { colorBuffer(); depthBuffer() }

        val camera = OrbitalCamera(
                eye = Vector3(x = -22.5, y = 277.95, z = 365.84),
                lookAt = Vector3(x = -68.55, y = -180.0, z = 36.86),
                //eye = Vector3(0.0, 0.0, depth.toDouble() / 2.0),
                //lookAt = Vector3.ZERO,
                fov = 60.0
        )

        val particleGeometry = vertexBuffer(vertexFormat { position(3) }, 12).apply {
            val points = Circle(Vector2.ZERO, particleRadius).contour.equidistantPositions(vertexCount)
            put {
                for (point in points) {
                    write(point.xy0)
                }
            }
        }

        val initialParticlePositions = poissonDiskSampling(width.toDouble(), height.toDouble(), poissonRadius).map {
            (it - Vector2(width / 2.0, height / 2.0)).let { Vector3(it.x, 0.0, it.y) }
        }

        val particleTransforms = vertexBuffer(vertexFormat {
            attribute("transform", VertexElementType.MATRIX44_FLOAT32)
        }, initialParticlePositions.size)

        var segments = emptyList<Segment3D>()

        fun waveOffset(pos: Vector3, origin: Vector3): Vector3 {
            val delta = origin - pos
            val lengthFactor = delta.length / (width * 0.7)
            return delta.normalized * params.magnitude * sin(10 * PI * (time - lengthFactor))
        }

        fun waveOffset(pos: Vector3): Vector3 {
            return waveOffset(pos, waveOrigin1)
        }

        fun update() {
            time = ((frameCount - 1) % TOTAL_FRAMES) / TOTAL_FRAMES.toDouble()
            camera.update(deltaTime)

            grid.clear()

            val positions = initialParticlePositions.mapIndexed { _, pos ->
                val noisePos = Vector2(
                        params.scale * pos.x + cos(2 * PI * time),
                        params.scale * pos.z + sin(2 * PI * time),
                )
                val dx1 = params.magnitude * simplex(1, noisePos)
                val dz1 = params.magnitude * simplex(2, noisePos)

                val waveOffset = waveOffset(pos)
                Vector3(pos.x + dx1, waveOffset.length / 2.0, pos.z + dz1) + waveOffset
            }

            particleTransforms.put {
                for (pos in positions) {

                    grid.add(pos.xz, pos)

                    val tx = transform {
                        translate(pos)
                    }
                    write(tx)
                }
            }

            val newSegments = HashSet<Segment3D>()
            for (pos in positions) {
                val key = pos.xz
                val neighbors = grid.queryRange(
                        Rectangle.fromCenter(key, 2 * plexusThreshold, 2 * plexusThreshold)
                ).filter {
                    it !== pos && it.distanceTo(pos) <= plexusThreshold
                }

                for (neighbor in neighbors) {
                    val segment = Segment3D(pos, neighbor)
                    val reversed = segment.reverse
                    if (segment !in newSegments && reversed !in newSegments) {
                        newSegments.add(segment)
                    }
                }
            }
            segments = newSegments.toList()
        }

        val composite = compose {
            draw {

                drawer.isolatedWithTarget(blurTarget) {
                    camera.applyTo(drawer)

                    drawer.clear(ColorRGBa.BLACK)

                    drawer.drawStyle.blendMode = BlendMode.ADD

                    drawer.shadeStyle = shadeStyle {
                        vertexTransform = """
                            x_viewMatrix *= i_transform;
                            x_viewMatrix[0].xyz = vec3(1, 0, 0);
                            x_viewMatrix[1].xyz = vec3(0, 1, 0);
                            x_viewMatrix[2].xyz = vec3(0, 0, 1);
                        """.trimIndent()

                        fragmentTransform = """
                            //x_fill.rgb *= smoothstep(0.0, 0.9, p_radius - length(va_position));
                        """.trimIndent()
                        parameter("radius", particleRadius)
                    }

                    drawer.fill = colorMap[0.0]
                    drawer.stroke = null
                    drawer.vertexBufferInstances(
                            listOf(particleGeometry),
                            listOf(particleTransforms),
                            DrawPrimitive.TRIANGLE_FAN,
                            particleTransforms.vertexCount
                    )
                    drawer.shadeStyle = null

                    drawer.strokeWeight = 3.0
                    val weights = segments.map {
                        it.length.rangeMap(0.0, 35.0, 16.0, 3.0)
                    }
                    val colors = segments.map { segment ->
                        var lengthFactor = clamp(1 - segment.length / plexusThreshold, 0.0, 1.0)
                        lengthFactor = 1 - (1 - lengthFactor).pow(1.5)
                        colorMap[1 - lengthFactor]//.opacify(0.3 + 0.7 * lengthFactor)
                    }
                    drawer.segments(segments, weights, colors)
                }

                drawer.image(blurTarget.colorBuffer(0))
            }
//            post(blur) {
//                depthBuffer = blurTarget.depthBuffer!!
//                focusPoint = 0.3
//                focusScale = 0.1
//            }
            post(GaussianBloom()) {
                sigma = 0.1
                shape = 0.1
            }
            post(FrameBlur())
        }

        val videoTarget = renderTarget(width, height) { colorBuffer() }
        val videoWriter = VideoWriter.create()
            .size(width, height)
            .frameRate(240)
            .output("C://video/plexus-waves-2.mp4")
        videoWriter.start()

        extend {
            update()

            drawer.isolatedWithTarget(videoTarget) {
                composite.draw(this)
            }
            drawer.image(videoTarget.colorBuffer(0))

            if (frameCount > DELAY_FRAMES) {
                videoWriter.frame(videoTarget.colorBuffer(0))
            }

            if (frameCount >= TOTAL_FRAMES * LOOPS + DELAY_FRAMES) {
                videoWriter.stop()
                application.exit()
            }
        }
    }
}