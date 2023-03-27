package uppaal.model

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Element
import org.simpleframework.xml.Root

@Root(name = "boundarypoint")
class BoundaryPoint() : UppaalPojo {
    companion object {
        const val ENTRY = "entry"
        const val EXIT = "exit"
    }

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


    override var parent: UppaalPojo? = null


    constructor(id: String, x: Int, y: Int, kind: String, name: Name?) : this() {
        this.id = id
        this.x = x
        this.y = y
        this.kind = kind
        this.name = name
    }


    fun clone(): BoundaryPoint = BoundaryPoint(id, x, y, kind, name?.clone())
}