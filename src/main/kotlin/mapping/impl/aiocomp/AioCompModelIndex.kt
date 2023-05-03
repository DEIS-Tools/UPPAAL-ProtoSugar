package mapping.impl.aiocomp

import tools.indexing.tree.Model
import tools.parsing.GuardedConfre
import tools.parsing.GuardedParseTree
import uppaal.UppaalPath
import uppaal.model.BoundaryPoint
import uppaal.model.SubTemplateReference
import uppaal.model.Template
import uppaal.model.Transition
import java.util.Stack


class LinkedBoundary(val referencingBoundaryPoint: BoundaryPoint, val referencedBoundaryPoint: BoundaryPoint,
                     val subTemplateReference: SubTemplateReference,
                     val referencingTemplateInfo: TaTemplateInfo, val referencedTemplateInfo: TaTemplateInfo)
{
    val isEntry = referencedBoundaryPoint.kind == BoundaryPoint.ENTRY

    init {
        referencingTemplateInfo.linkedBoundaryFromReferencerID[referencingBoundaryPoint.id] = this
        referencedTemplateInfo.linkedBoundariesFromReferencedID
            .getOrPut(referencedBoundaryPoint.id) { mutableListOf() }
            .add(this)
    }
}

enum class PathType { COMPLETE, CYCLIC, INCOMPLETE }
class BoundaryPath(val type: PathType, val path: List<BoundaryPathNode>)
class BoundaryPathNode(val edge: Transition, val templateInfo: TaTemplateInfo) {
    val edgeInfo: BoundaryEdgeInfo = templateInfo.boundaryEdges[edge]!!
}

enum class EndType { NORMAL, LOCAL_BOUNDARY, REFERENCED_BOUNDARY }
class BoundaryEdgeInfo {
    var sourceType = EndType.NORMAL
    var targetType = EndType.NORMAL

    val isOnBoundary get () = sourceType != EndType.NORMAL || targetType != EndType.NORMAL
}

class LocalBoundaryInfo(val entries: List<BoundaryPoint>, val exits: List<BoundaryPoint>, val blanks: List<IndexedValue<BoundaryPoint>>) {
    val duplicatesByName = (entries + exits).groupBy { it.name!!.content }.filter { it.value.size > 1 }
    val idsOfDuplicateNames = duplicatesByName.values.flatten().map { it.id }

    val nameToBoundaryPoint = (entries + exits).filter { it.id !in idsOfDuplicateNames }.associateBy { it.name!!.content }
    val validEntryIDs = entries.filter { it.id !in idsOfDuplicateNames }.map { it.id }
    val validExitIDs = exits.filter { it.id !in idsOfDuplicateNames }.map { it.id }

    fun getSafeDirection(boundaryPointName: String): String? = when (boundaryPointName) {
        in duplicatesByName -> null
        else -> nameToBoundaryPoint[boundaryPointName]?.kind
    }

    companion object {
        @JvmStatic
        fun from(template: Template): LocalBoundaryInfo? {
            if (!AioCompModelIndex.isSubTemplate(template))
                return null

            return LocalBoundaryInfo(
                template.boundarypoints.filter { it.kind == BoundaryPoint.ENTRY && it.name != null },
                template.boundarypoints.filter { it.kind == BoundaryPoint.EXIT && it.name != null },
                template.boundarypoints.withIndex().filter { it.value.name == null }
            )
        }
    }
}

class SubTemplateUsage {
    var arguments: List<GuardedParseTree> = emptyList()
    var referencedInfo: TaTemplateInfo? = null
    var namesToReplace = HashMap<String, String>()
}

class TaTemplateInfo(
    val element: Template,
    val path: UppaalPath,
    val trueName: String,
    val isRedefinition: Boolean)
{
    val boundaryInfo = LocalBoundaryInfo.from(element)
    val linkedBoundaryFromReferencerID = HashMap<String, LinkedBoundary>()
    val linkedBoundariesFromReferencedID = HashMap<String, MutableList<LinkedBoundary>>()
    val subTemplateInstances = LinkedHashMap<SubTemplateReference, SubTemplateUsage>()
    val boundaryEdges = HashMap<Transition, BoundaryEdgeInfo>()

    val isSubTemplate = boundaryInfo != null
    val isSubTemplateUser = element.subtemplatereferences.isNotEmpty()
    val subTemOrUser = isSubTemplate || isSubTemplateUser

    var hasPassedIndexing = false
    var hasPassedReferenceCheck = false
    var hasPassedCycleCheck = true // Is set to false if necessary
    var hasRedefinition = false
    val canBeMapped: Boolean get() = hasPassedIndexing && hasPassedReferenceCheck && hasPassedCycleCheck && !hasRedefinition && subTemplateInstances.values.all { it.referencedInfo!!.canBeMapped }

    var infixedName: String? = null
}


class AioCompModelIndex {
    companion object {
        @JvmStatic
        fun isSubTemplate(template: Template): Boolean
                = isSubTemplate(template.name.content)

        @JvmStatic
        fun isSubTemplate(name: String): Boolean
                = name.startsWith("__") && name.getOrNull(2) != '_'

        @JvmStatic
        fun trueName(name: String): String
                = (if (isSubTemplate(name)) name.drop(2) else name).trim()
    }

    lateinit var model: Model
    lateinit var exprConfre: GuardedConfre
    lateinit var selectConfre: GuardedConfre

    val taTemplates = LinkedHashMap<String, TaTemplateInfo>()
    val subTemplates = LinkedHashMap<String, TaTemplateInfo>()
    val rootSubTemUsers = LinkedHashMap<String, TaTemplateInfo>()


    operator fun get(element: Template) = get(element.name.content)
    operator fun get(name: String) = taTemplates[trueName(name)]


    fun register(element: Template, path: UppaalPath): TaTemplateInfo {
        val trueName = trueName(element.name.content)
        val isRedefinition = trueName in taTemplates
        val info = TaTemplateInfo(element, path, trueName, isRedefinition)

        if (info.isRedefinition) {
            taTemplates[trueName]!!.hasRedefinition = true
            return info
        }

        taTemplates[trueName] = info
        if (info.isSubTemplate)
            subTemplates[trueName] = info
        else if (info.isSubTemplateUser)
            rootSubTemUsers[trueName] = info

        return info
    }


    fun getCircularReferences(): HashMap<LinkedHashSet<TaTemplateInfo>, UppaalPath> {
        val cycles = HashMap<LinkedHashSet<TaTemplateInfo>, UppaalPath>()

        for (elem in taTemplates.values.filter { it.isSubTemplateUser })
            for (cycle in getCircularReferences(elem, mutableListOf(elem)))
                cycles.putIfAbsent(cycle.second, cycle.first)

        return cycles
    }

    private fun getCircularReferences(current: TaTemplateInfo, path: MutableList<TaTemplateInfo>):
            Sequence<Pair<UppaalPath, LinkedHashSet<TaTemplateInfo>>>
    {
        if (!current.hasPassedReferenceCheck)
            return emptySequence()

        return sequence {
            for (usage in current.subTemplateInstances)
                if (usage.value.referencedInfo!! in path) {
                    val index = current.element.subtemplatereferences.indexOf(usage.key)
                    val uppaalPath = current.path.extend(usage.key, index)
                    val cycle = linkedSetOf(*path.dropWhile { it != usage.value.referencedInfo }.toTypedArray()) // Must use array and "unpack" to get correct return types
                    yield(Pair(uppaalPath, cycle))
                }
                else {
                    path.add(usage.value.referencedInfo!!)
                    yieldAll(getCircularReferences(usage.value.referencedInfo!!, path))
                    path.removeLast()
                }
        }
    }


    fun getBoundaryPaths(): List<BoundaryPath> {
        val remainingBoundaryEdges = HashSet<Transition>()
        val startEdges = mutableListOf<BoundaryPathNode>()

        for (taTemplate in taTemplates.values)
            for (boundaryEdge in taTemplate.boundaryEdges) {
                remainingBoundaryEdges.add(boundaryEdge.key)
                if (boundaryEdge.value.sourceType == EndType.NORMAL) // All paths start in normal location
                    startEdges.add(BoundaryPathNode(boundaryEdge.key, taTemplate))
            }

        val paths = ArrayList<BoundaryPath>()
        for (node in startEdges)
            for (path in getBoundaryPaths(mutableListOf(node), Stack())) {
                paths.add(path)
                for (pathNode in path.path)
                    remainingBoundaryEdges.remove(pathNode.edge)
            }

        // TODO: Handle remaining edges (only problem can be cyclic paths that are not connected to a normal location in any way)
        //  Incomplete paths with no "normal start"?

        return paths
    }

    private fun getBoundaryPaths(path: List<BoundaryPathNode>, referenceChain: List<SubTemplateReference>): Sequence<BoundaryPath> {
        val current = path.last()
        if (!current.templateInfo.hasPassedReferenceCheck)
            return emptySequence()

        // TODO: Ignore complete paths since they are not used for merging (which is done layer-by-layer)
        if (current.edgeInfo.targetType == EndType.NORMAL)
            return sequenceOf(BoundaryPath(PathType.COMPLETE, path))

        val cyclicPath = path.dropWhile { !sameTargetBoundaryPoint(it, current) }
        if (cyclicPath.size > 1)
            return sequenceOf(BoundaryPath(PathType.CYCLIC, cyclicPath))

        // TODO: Perhaps ignore incomplete paths? Or make a warning?
        val nextEdges = getNextEdges(path, referenceChain)
        if (nextEdges.isEmpty())
            return sequenceOf(BoundaryPath(PathType.INCOMPLETE, path))

        return sequence {
            for ((nextEdge, subTemplateReference) in nextEdges) {
                yieldAll(getBoundaryPaths(
                    path + nextEdge,
                    if (subTemplateReference == null) referenceChain.dropLast(1)
                    else referenceChain + subTemplateReference
                ))
            }
        }
    }

    private fun sameTargetBoundaryPoint(it: BoundaryPathNode, current: BoundaryPathNode) =
        it.templateInfo == current.templateInfo && it.edge.target.ref == current.edge.target.ref

    private fun getNextEdges(path: List<BoundaryPathNode>, referenceChain: List<SubTemplateReference>): List<Pair<BoundaryPathNode, SubTemplateReference?>> {
        val pathLast = path.last()
        val referenceLast = referenceChain.lastOrNull()

        if (pathLast.edgeInfo.targetType == EndType.LOCAL_BOUNDARY) { // Moving "up", into a sub-template reference
            val backLinks = pathLast.templateInfo.linkedBoundariesFromReferencedID[pathLast.edge.target.ref]
                ?: return emptyList()

            // In case path "moved in" from a specific sub-template-reference, only allow leaving through that sub-template-reference
            return backLinks
                .filter { link -> referenceLast == null || referenceLast == link.subTemplateReference }
                .flatMap { link ->
                    // Get the edges from the sub-template that leave the local boundary point that is referenced by the last boundary-point in the current path.
                    link.referencingTemplateInfo.boundaryEdges
                        .filter { it.key.source.ref == link.referencingBoundaryPoint.id }
                        .map { Pair(BoundaryPathNode(it.key, link.referencingTemplateInfo), null) } // Return null to signify "move up" since "move up" does not apply any new restrictions
                }
        }
        else { // Moving "down", into a sub-template
            val link = pathLast.templateInfo.linkedBoundaryFromReferencerID[pathLast.edge.target.ref]
                ?: return emptyList()

            // Get the edges from the sub-template that leave the local boundary point that is referenced by the last boundary-point in the current path.
            return link.referencedTemplateInfo.boundaryEdges
                .filter { it.key.source.ref == link.referencedBoundaryPoint.id }
                .map { Pair(BoundaryPathNode(it.key, link.referencedTemplateInfo), link.subTemplateReference) }
        }
    }
}