package uppaal

import org.simpleframework.xml.Serializer
import org.simpleframework.xml.core.Persister
import uppaal.model.Nta
import uppaal.walkers.ParentModelWalker
import java.io.StringWriter

class ModelSerializer {
    companion object {
        private val serializer: Serializer = Persister()

        @JvmStatic
        fun deserialize(modelString: String): Nta {
            val ntaStart = modelString.indexOf("<nta>")
            val ntaText = modelString.substring(ntaStart)

            val nta = serializer.read(Nta::class.java, ntaText)
            nta.schemaInfo = modelString.substring(0, ntaStart)
            ParentModelWalker().doWalk(nta)

            return nta
        }

        @JvmStatic
        fun serialize(model: Nta): String =
            StringWriter().use {
                serializer.write(model, it)
                model.schemaInfo + it.buffer.toString()
            }
    }
}