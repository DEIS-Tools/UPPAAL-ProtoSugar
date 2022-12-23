package uppaal.model

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Root
import kotlin.collections.ArrayList

@Root(name = "transition")
class Transition() : UppaalPojo { // Primary constructor needed for XML deserialization
    @field:Attribute(name = "id")
    lateinit var id: String

    @field:Element(name = "source")
    lateinit var source: Source

    @field:Element(name = "target")
    lateinit var target: Target

    @field:ElementList(name = "label", inline = true, required = false)
    var labels: MutableList<Label> = ArrayList()

    @field:ElementList(name = "nail", inline = true, required = false)
    var nails: MutableList<Nail> = ArrayList()

    constructor(id: String, source: Source, target: Target, labels: MutableList<Label> = arrayListOf(), nails: MutableList<Nail> = arrayListOf()) : this() {
        this.id = id
        this.source = source
        this.target = target
        this.labels = labels
        this.nails = nails
    }
}