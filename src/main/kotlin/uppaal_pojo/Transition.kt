package uppaal_pojo

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Root
import java.util.*

@Root(name = "transition")
class Transition : UppaalPojo {
    @field:Attribute(name = "id")
    lateinit var id: String

    @field:Element(name = "source")
    lateinit var source: Source

    @field:Element(name = "target")
    lateinit var target: Target

    @field:ElementList(name = "label", inline = true, required = false)
    var labels: MutableList<Label>? = null

    @field:ElementList(name = "nail", inline = true, required = false)
    var nails: List<Nail>? = null
}