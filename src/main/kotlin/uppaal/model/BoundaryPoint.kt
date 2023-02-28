package uppaal.model

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Element
import org.simpleframework.xml.Root

@Root(name = "boundarypoint")
class BoundaryPoint : UppaalPojo {
    @field:Attribute(name = "id")
    lateinit var id: String

    @field:Attribute(name = "x")
    var x: Int = 0

    @field:Attribute(name = "y")
    var y: Int = 0

    @field:Attribute(name = "direction")
    lateinit var kind: String

    @field:Element(name = "name", required = false)
    var name: Name? = null
}