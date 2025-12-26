package com.example.jetbrainsplugin.parser.gumtree

import com.example.jetbrainsplugin.ACRToolWindowFactory
import com.example.jetbrainsplugin.actions.LLMClient
import com.github.gumtreediff.client.Run
import com.github.gumtreediff.matchers.MappingStore
import com.github.gumtreediff.matchers.Matchers
import com.github.gumtreediff.tree.Tree
import com.intellij.openapi.diagnostic.Logger

class Matcher {

    init {
        Run.initGenerators()
        Run.initMatchers()
        Run.initClients()
    }

    companion object {
        private val logger = Logger.getInstance(Matcher::class.java)
    }

    // Each triple is the baseline node plus the matched node in modified/patched if they exist
    data class NodeTriple(val baseline: Tree, val modified: Tree?, val patched: Tree?)

    // Use built-in method for basic structural check
    private fun areSubtreesIdentical(n1: Tree?, n2: Tree?): Boolean {
        if (n1 == null && n2 == null) return true
        if (n1 == null || n2 == null) return false
        return n1.isIsomorphicTo(n2)
    }

    /**
     * Our logic for which node to pick among (baseline, modified, patched).
     * We do naive checks for identical subtrees, then fallback to baseline.
     */
    private fun decideTriple(triple: NodeTriple): Tree {
        val (base, mod, pat) = triple
        val baseModSame = areSubtreesIdentical(base, mod)
        val basePatSame = areSubtreesIdentical(base, pat)
        val modPatSame  = areSubtreesIdentical(mod, pat)

        return when {
            baseModSame && basePatSame -> base.deepCopy()
            baseModSame                -> pat?.deepCopy() ?: base.deepCopy()
            basePatSame                -> mod?.deepCopy() ?: base.deepCopy()
            mod != null && modPatSame  -> mod.deepCopy()
            else                       -> base.deepCopy()
        }
    }

    // -------------------------------------------------------------------------
    //  MAPPINGS: "modified -> baseline" / "patched -> baseline" & "baseline -> merged"
    // -------------------------------------------------------------------------

    // For any node in modified/patched that existed in baseline, map it to its baseline node
    private val modifiedToBaseline = mutableMapOf<Tree, Tree>()
    private val patchedToBaseline  = mutableMapOf<Tree, Tree>()

    // For any baseline node, which node did we choose in the final merged AST?
    private val baselineToMerged   = mutableMapOf<Tree, Tree>()

    /**
     * Create the tripleMap for baseline nodes, and also fill in the "modifiedToBaseline" /
     * "patchedToBaseline" dictionaries. So we know which baseline node each mod/patched node corresponds to.
     */
    private fun computeTripleMap(
        baselineTree: Tree,
        modifiedTree: Tree,
        patchedTree: Tree
    ): Map<Tree, NodeTriple> {

        val tripleMap = mutableMapOf<Tree, NodeTriple>()

        val matcher = Matchers.getInstance().matcher
        val modMapping: MappingStore = matcher.match(baselineTree, modifiedTree)
        val patMapping: MappingStore = matcher.match(baselineTree, patchedTree)

        // Fill "modifiedToBaseline" & "patchedToBaseline" from the mappings
        baselineTree.preOrder().forEach { baseNode ->
            val mNode = modMapping.getDstForSrc(baseNode)
            if (mNode != null) {
                modifiedToBaseline[mNode] = baseNode
            }
            val pNode = patMapping.getDstForSrc(baseNode)
            if (pNode != null) {
                patchedToBaseline[pNode] = baseNode
            }
        }

        // Also build NodeTriple for each baseline node
        baselineTree.preOrder().forEach { baseNode ->
            val modNode = modMapping.getDstForSrc(baseNode)
            val patNode = patMapping.getDstForSrc(baseNode)

            // If the node is root, add no matter what
            if (baseNode.isRoot) {
                tripleMap[baseNode] = NodeTriple(baseNode, modNode, patNode)
            } else if (modNode == null && patNode == null) {
                // means node is deleted in both => skip
            } else {
                tripleMap[baseNode] = NodeTriple(baseNode, modNode, patNode)
            }
        }

        return tripleMap
    }

    // -------------------------------------------------------------------------
    //  BASELINE MERGE
    // -------------------------------------------------------------------------

    /**
     * Insert child into parent's children in the correct baseline-based index.
     */
    private fun addChildInOrder(
        parent: Tree,
        child: Tree?,
        baselineParent: Tree,
        baselineChild: Tree
    ) {
        if (child == null) return
        val baselineIndex = baselineParent.children.indexOf(baselineChild)
        val current = parent.children.toMutableList()
        when {
            baselineIndex < 0 -> current.add(child)
            baselineIndex >= current.size -> current.add(child)
            else -> current.add(baselineIndex, child)
        }
        parent.setChildren(current)
    }

    /**
     * Recursively merge baseline nodes using tripleMap, storing the final chosen node
     * in "baselineToMerged" for each baseline node.
     */
    private fun mergeTrees(
        baselineNode: Tree,
        tripleMap: Map<Tree, NodeTriple>
    ): Tree {
        val triple = tripleMap[baselineNode] ?: NodeTriple(baselineNode, baselineNode, baselineNode)
        val chosenNode = decideTriple(triple)

        // Clear children to avoid duplicates
        chosenNode.setChildren(mutableListOf())

        // Record the final chosen node for baselineNode
        baselineToMerged[baselineNode] = chosenNode

        // Recur on baseline's children
        baselineNode.children.forEach { bChild ->
            val mergedChild = mergeTrees(bChild, tripleMap)
            addChildInOrder(chosenNode, mergedChild, baselineNode, bChild)
        }
        return chosenNode
    }

    // -------------------------------------------------------------------------
    //  HANDLE NEW (UNMATCHED) NODES IN MOD/PATCH
    // -------------------------------------------------------------------------

    /**
     * Return all "top-most unmatched" nodes in sourceTree w.r.t. mergedTree.
     * We use a standard matching approach, but only want the unmatched nodes whose parent is matched.
     */
    private fun collectTopMostUnmatchedNodes(
        sourceTree: Tree, mergedTree: Tree
    ): List<Tree> {
        // We'll do a match to see which subtrees map over
        val match = Matchers.getInstance().matcher
        val store = match.match(sourceTree, mergedTree)

        val unmatchedSet = mutableSetOf<Tree>()
        sourceTree.preOrder().forEach { n ->
            val mapped = store.getDstForSrc(n)
            if (mapped == null) {
                unmatchedSet.add(n)
            }
        }
        val topMost = mutableListOf<Tree>()
        for (node in unmatchedSet) {
            val parent = node.parent
            // if parent is null or matched => node is top-most unmatched
            if (parent == null || parent !in unmatchedSet) {
                topMost.add(node)
            }
        }
        return topMost
    }

    /**
     * Climb up the parent chain of 'node' in the modified/patched AST,
     * looking for an ancestor that was matched to a baseline node.
     * If found, we retrieve that baseline node's merged counterpart from 'baselineToMerged'.
     * This avoids isIsomorphicTo checks entirely.
     */
    private fun findParentInMerged(
        node: Tree,
        modOrPatToBaseline: Map<Tree, Tree>, // either modifiedToBaseline or patchedToBaseline
    ): Tree? {
        var current = node.parent
        while (current != null) {
            val baselineNode = modOrPatToBaseline[current]
            if (baselineNode != null) {
                // found an ancestor that existed in baseline => get its merged counterpart
                return baselineToMerged[baselineNode]
            }
            // otherwise keep climbing
            current = current.parent
        }
        // no baseline ancestor found => might attach to root or skip
        return null
    }

    private fun addNewNode(newNode: Tree, parent: Tree) {
        val copy = newNode.deepCopy()
        val kids = parent.children.toMutableList()
        kids.add(copy)
        parent.setChildren(kids)
    }

    /**
     * For each newly added node in Modified or Patched, we find
     * the nearest ancestor that existed in baseline -> get that ancestor's merged node -> add new node there.
     */
    private fun handleExtraNodes(
        modifiedTree: Tree,
        patchedTree: Tree,
        mergedTree: Tree
    ) {
        // gather top-most unmatched from mod
        val unmatchedMod = collectTopMostUnmatchedNodes(modifiedTree, mergedTree).toMutableList()
        // gather top-most unmatched from pat
        val unmatchedPat = collectTopMostUnmatchedNodes(patchedTree, mergedTree).toMutableList()

        logger.info("===== Unmatched Nodes =====")
        logger.info("Modified has: ${unmatchedMod.size}, Patched has: ${unmatchedPat.size}")

        // For each unmatched node in mod, see if there's an identical unmatched node in patch
        val toRemove = mutableSetOf<Tree>()
        for (modNode in unmatchedMod) {
            val samePatNode = unmatchedPat.find { areSubtreesIdentical(modNode, it) }

            // climb up to find parent's counterpart in merged
            val parentInMerged = findParentInMerged(modNode, modifiedToBaseline)
            if (parentInMerged != null) {
                addNewNode(modNode, parentInMerged)
                if (samePatNode != null) {
                    toRemove.add(samePatNode)
                }
            } else {
                // fallback logic if no baseline ancestor found
                // e.g. attach to root of merged or skip
            }
        }
        // remove from patch
        unmatchedPat.removeAll(toRemove)

        // leftover unmatched in patch
        for (patNode in unmatchedPat) {
            val parentInMerged = findParentInMerged(patNode, patchedToBaseline)
            if (parentInMerged != null) {
                addNewNode(patNode, parentInMerged)
            } else {
                // fallback logic
            }
        }
    }

    // -------------------------------------------------------------------------
    //  MAIN ENTRY POINT
    // -------------------------------------------------------------------------

    fun performMergeOnTrees(
        baselineTree: Tree,
        modifiedTree: Tree,
        patchedTree: Tree
    ): String {
//        logger.info("===== Baseline Tree =====")
//        logger.info(baselineTree.toTreeString())

        // Build the triple map and the matched node dictionaries
        val tripleMap = computeTripleMap(baselineTree, modifiedTree, patchedTree)

        // Merge baseline
        val mergedTree = mergeTrees(baselineTree, tripleMap)

        // Now handle brand-new nodes from mod/patched
        handleExtraNodes(modifiedTree, patchedTree, mergedTree)

        val AST = mergedTree.toTreeString()
        val javaCode = LLMClient().convertASTToCode(AST)
        logger.info("===== Final Merged Java Code =====")
        logger.info(javaCode)

        return javaCode
    }
}
