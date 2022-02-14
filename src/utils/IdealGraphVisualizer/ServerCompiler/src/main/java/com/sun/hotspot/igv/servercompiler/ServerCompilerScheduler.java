/*
 * Copyright (c) 1998, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.sun.hotspot.igv.servercompiler;

import com.sun.hotspot.igv.data.InputBlock;
import com.sun.hotspot.igv.data.InputEdge;
import com.sun.hotspot.igv.data.InputGraph;
import com.sun.hotspot.igv.data.InputNode;
import com.sun.hotspot.igv.data.services.Scheduler;
import java.util.*;
import org.openide.ErrorManager;
import org.openide.util.lookup.ServiceProvider;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.impl.SlowSparseNumberedGraph;
import com.ibm.wala.util.graph.dominators.Dominators;

/**
 *
 * @author Thomas Wuerthinger
 */
@ServiceProvider(service=Scheduler.class)
public class ServerCompilerScheduler implements Scheduler {

    private static class Node {

        public InputNode inputNode;
        public Set<Node> succs = new HashSet<>();
        public List<Node> preds = new ArrayList<>();
        public List<Character> predIndices = new ArrayList<>();
        public InputBlock block;
        public boolean isBlockProjection;
        public boolean isBlockStart;
        public boolean isCFG;

        @Override
        public String toString() {
            return inputNode.toString();
        }
    }
    private InputGraph graph;
    private Collection<Node> nodes;
    private Map<InputNode, Node> inputNodeToNode;
    private Vector<InputBlock> blocks;
    private Map<InputBlock, InputBlock> dominatorMap;
    private Map<InputBlock, Integer> blockIndex;
    private InputBlock[][] commonDominator;
    private static final Comparator<InputEdge> edgeComparator = new Comparator<InputEdge>() {

        @Override
        public int compare(InputEdge o1, InputEdge o2) {
            return o1.getToIndex() - o2.getToIndex();
        }
    };

    public void buildBlocks() {

        // Initialize data structures.
        blocks = new Vector<>();
        Node root = findRoot();
        if (root == null) {
            return;
        }
        Stack<Node> stack = new Stack<>();
        Set<Node> visited = new HashSet<>();
        Map<InputBlock, Set<Node>> terminators = new HashMap<>();
        // Pre-compute control successors of each node, excluding self edges.
        Map<Node, Set<Node>> controlSuccs = new HashMap<>();
        for (Node n : nodes) {
            if (n.isCFG) {
                Set<Node> nControlSuccs = new HashSet<>();
                for (Node s : n.succs) {
                    if (s.isCFG && s != n) {
                        nControlSuccs.add(s);
                    }
                }
                controlSuccs.put(n, nControlSuccs);
            }
        }
        stack.add(root);
        // Start from 1 to follow the style of compiler-generated CFGs.
        int blockCount = 1;
        InputBlock rootBlock = null;

        // Traverse the control-flow subgraph forwards, starting from the root.
        while (!stack.isEmpty()) {
            // Pop a node, mark it as visited, and create a new block.
            Node n = stack.pop();
            if (visited.contains(n)) {
                continue;
            }
            visited.add(n);
            InputBlock block = graph.addBlock(Integer.toString(blockCount));
            blocks.add(block);
            if (n == root) {
                rootBlock = block;
            }
            blockCount++;
            Set<Node> blockTerminators = new HashSet<Node>();
            // Move forwards until a terminator node is found, assigning all
            // visited nodes to the current block.
            while (true) {
                // Assign n to current block.
                n.block = block;
                if (controlSuccs.get(n).size() == 0) {
                    // No successors: end the block.
                    blockTerminators.add(n);
                    break;
                } else if (controlSuccs.get(n).size() == 1) {
                    // One successor: end the block if it is a block start node.
                    Node s = controlSuccs.get(n).iterator().next();
                    if (s.isBlockStart) {
                        // Block start: end the block.
                        blockTerminators.add(n);
                        stack.push(s);
                        break;
                    } else {
                        // Not a block start: keep filling the current block.
                        n = s;
                    }
                } else {
                    // Multiple successors: end the block.
                    for (Node s : controlSuccs.get(n)) {
                        if (s.isBlockProjection && s != root) {
                            // Assign block projections to the current block,
                            // and push their successors to the stack. In the
                            // normal case, we would expect control projections
                            // to have only one successor, but there are some
                            // intermediate graphs (e.g. 'Before RemoveUseless')
                            // where 'IfX' nodes flow both to 'Region' and
                            // (dead) 'Safepoint' nodes.
                            s.block = block;
                            blockTerminators.add(s);
                            for (Node ps : controlSuccs.get(s)) {
                                stack.push(ps);
                            }
                        } else {
                            blockTerminators.add(n);
                            stack.push(s);
                        }
                    }
                    break;
                }
            }
            terminators.put(block, blockTerminators);
        }

        // Add block edges based on terminator successors. Note that a block
        // might have multiple terminators preceding the same successor block.
        for (Map.Entry<InputBlock, Set<Node>> terms : terminators.entrySet()) {
            // Unique set of terminator successors.
            Set<Node> uniqueSuccs = new HashSet<>();
            for (Node t : terms.getValue()) {
                for (Node s : controlSuccs.get(t)) {
                    if (s.block != rootBlock) {
                        uniqueSuccs.add(s);
                    }
                }
            }
            for (Node s : uniqueSuccs) {
                graph.addBlockEdge(terms.getKey(), s.block);
            }
        }

        // Fill the blocks.
        for (Node n : nodes) {
            InputBlock block = n.block;
            if (block != null) {
                block.addNode(n.inputNode.getId());
            }
        }

        // Compute block index map for dominator computation.
        int z = 0;
        blockIndex = new HashMap<>(blocks.size());
        for (InputBlock b : blocks) {
            blockIndex.put(b, z);
            z++;
        }
    }

    private String getBlockName(InputNode n) {
        return n.getProperties().get("block");
    }

    @Override
    public Collection<InputBlock> schedule(InputGraph graph) {
        if (graph.getNodes().isEmpty()) {
            return Collections.emptyList();
        }

        if (graph.getBlocks().size() > 0) {
            Collection<InputNode> tmpNodes = new ArrayList<>(graph.getNodes());
            for (InputNode n : tmpNodes) {
                String block = getBlockName(n);
                if (graph.getBlock(n) == null) {
                    graph.getBlock(block).addNode(n.getId());
                    assert graph.getBlock(n) != null;
                }
            }
            return graph.getBlocks();
        } else {
            nodes = new ArrayList<>();
            inputNodeToNode = new HashMap<>(graph.getNodes().size());

            this.graph = graph;
            if (!hasCategoryInformation()) {
                ErrorManager.getDefault().log(ErrorManager.WARNING,
                    "Cannot find node category information in the input graph. " +
                    "The control-flow graph will not be approximated.");
                return null;
            }
            buildUpGraph();
            markCFGNodes();
            connectOrphansAndWidows();
            buildBlocks();
            buildDominators();
            buildCommonDominators();
            scheduleLatest();

            InputBlock noBlock = null;
            for (InputNode n : graph.getNodes()) {
                if (graph.getBlock(n) == null) {
                    if (noBlock == null) {
                        noBlock = graph.addBlock("(no block)");
                        blocks.add(noBlock);
                    }

                    graph.setBlock(n, noBlock);
                }
                assert graph.getBlock(n) != null;
            }

            check();

            return blocks;
        }
    }

    private void scheduleLatest() {

        // Mark all nodes reachable in backward traversal from root
        Set<Node> reachable = reachableNodes();

        // Schedule pinned nodes first.
        for (Node n : nodes) {
            if (!reachable.contains(n) ||
                n.block != null ||
                n.preds.isEmpty()) {
                continue;
            }
            Node ctrlIn = n.preds.get(0);
            if (!isControl(ctrlIn)) {
                continue;
            }
            // n is pinned to ctrlIn.
            InputBlock block = ctrlIn.block;
            n.block = block;
            block.addNode(n.inputNode.getId());
        }

        // Now schedule rest of reachable nodes.
        Set<Node> unscheduled = new HashSet<>();
        for (Node n : this.nodes) {
            if (n.block == null && reachable.contains(n)) {
                unscheduled.add(n);
            }
        }

        while (unscheduled.size() > 0) {
            boolean progress = false;

            Set<Node> newUnscheduled = new HashSet<>();
            for (Node n : unscheduled) {

                InputBlock block = null;

                for (Node s : n.succs) {
                    if (reachable.contains(s)) {
                        if (s.block == null) {
                            block = null;
                            break;
                        } else {
                            if (isPhi(s)) {
                                // Move inputs above their source blocks.
                                boolean found = false;
                                for (InputBlock srcBlock : sourceBlocks(n, s)) {
                                    found = true;
                                    if (block == null) {
                                        block = srcBlock;
                                    } else {
                                        int current = blockIndex.get(block),
                                            source  = blockIndex.get(srcBlock);
                                        block = commonDominator[current][source];
                                    }
                                }
                                if (!found) {
                                    // Can happen due to inconsistent phi-region pairs.
                                    block = s.block;
                                    ErrorManager.getDefault().log(ErrorManager.WARNING,
                                        "Could not find region of " + n + " in " + s + ", " +
                                        "this might affect the quality of the approximated schedule.");
                                }
                            } else if (block == null) {
                                block = s.block;
                            } else {
                                block = commonDominator[this.blockIndex.get(block)][blockIndex.get(s.block)];
                            }
                        }
                    }
                }

                if (block != null) {
                    n.block = block;
                    block.addNode(n.inputNode.getId());
                    progress = true;
                } else {
                    newUnscheduled.add(n);
                }
            }

            unscheduled = newUnscheduled;

            if (!progress) {
                break;
            }
        }

        // Finally, schedule unreachable nodes.
        Set<Node> curReachable = new HashSet<>(reachable);
        for (Node n : curReachable) {
            if (n.block != null) {
                for (Node s : n.succs) {
                    if (!reachable.contains(s)) {
                        markWithBlock(s, n.block, reachable);
                    }
                }
            }
        }

    }

    // Recomputes the input array of the given node, including empty slots.
    private Node[] inputArray(Node n) {
        Node[] inputs = new Node[Collections.max(n.predIndices) + 1];
        for (int i = 0; i < n.preds.size(); i++) {
            inputs[n.predIndices.get(i)] = n.preds.get(i);
        }
        return inputs;
    }

    // Finds the blocks from which node in flows into phi.
    private Set<InputBlock> sourceBlocks(Node in, Node phi) {
        Node reg = phi.preds.get(0);
        assert (reg != null);
        // Reconstruct the positional input arrays of phi-region pairs.
        Node[] phiInputs = inputArray(phi);
        Node[] regInputs = inputArray(reg);

        Set<InputBlock> srcBlocks = new HashSet<>();
        for (int i = 0; i < Math.min(phiInputs.length, regInputs.length); i++) {
            if (phiInputs[i] == in) {
                if (regInputs[i] != null) {
                    if (regInputs[i].isCFG) {
                        srcBlocks.add(regInputs[i].block);
                    } else {
                        ErrorManager.getDefault().log(ErrorManager.WARNING,
                            reg + " has non-control input, " +
                            "this might affect the quality of the approximated schedule.");
                    }
                } else {
                    ErrorManager.getDefault().log(ErrorManager.WARNING,
                        phi + " has input node without associated region, " +
                        "this might affect the quality of the approximated schedule.");
                }
            }
        }
        return srcBlocks;
    }

    private void markWithBlock(Node n, InputBlock b, Set<Node> reachable) {
        assert !reachable.contains(n);
        Stack<Node> stack = new Stack<>();
        stack.push(n);
        n.block = b;
        b.addNode(n.inputNode.getId());
        reachable.add(n);

        while (!stack.isEmpty()) {
            Node cur = stack.pop();
            for (Node s : cur.succs) {
                if (!reachable.contains(s)) {
                    reachable.add(s);
                    s.block = b;
                    b.addNode(s.inputNode.getId());
                    stack.push(s);
                }
            }

            for (Node s : cur.preds) {
                if (!reachable.contains(s)) {
                    reachable.add(s);
                    s.block = b;
                    b.addNode(s.inputNode.getId());
                    stack.push(s);
                }
            }
        }
    }

    public void buildCommonDominators() {
        commonDominator = new InputBlock[this.blocks.size()][this.blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            for (int j = 0; j < blocks.size(); j++) {
                commonDominator[i][j] = getCommonDominator(i, j);
            }
        }
    }

    public InputBlock getCommonDominator(int a, int b) {
        InputBlock ba = blocks.get(a);
        InputBlock bb = blocks.get(b);
        if (ba == bb) {
            return ba;
        }
        Set<InputBlock> visited = new HashSet<>();
        while (ba != null) {
            visited.add(ba);
            ba = dominatorMap.get(ba);
        }

        while (bb != null) {
            if (visited.contains(bb)) {
                return bb;
            }
            bb = dominatorMap.get(bb);
        }

        assert false;
        return null;
    }

    public void buildDominators() {
        dominatorMap = new HashMap<>(graph.getBlocks().size());
        if (blocks.size() == 0) {
            return;
        }

        Graph<InputBlock> CFG = SlowSparseNumberedGraph.make();
        for (InputBlock b : blocks) {
            CFG.addNode(b);
        }
        for (InputBlock p : blocks) {
            for (InputBlock s : p.getSuccessors()) {
                CFG.addEdge(p, s);
            }
        }

        InputBlock root = findRoot().block;
        Dominators<InputBlock> D = Dominators.make(CFG, root);

        for (InputBlock b : blocks) {
            InputBlock idom = D.getIdom(b);
            if (idom == null && b != root) {
                // getCommonDominator expects a single root node.
                idom = root;
            }
            dominatorMap.put(b, idom);
        }
    }

    // Whether b1 dominates b2.
    private boolean dominates(InputBlock b1, InputBlock b2) {
        InputBlock bi = b2;
        do {
            if (bi.equals(b1)) {
                return true;
            }
            bi = dominatorMap.get(bi);
        } while (bi != null);
        return false;
    }

    private boolean isRegion(Node n) {
        return n.inputNode.getProperties().get("name").equals("Region");
    }

    private boolean isPhi(Node n) {
        return n.inputNode.getProperties().get("name").equals("Phi");
    }

    private boolean isControl(Node n) {
        return n.inputNode.getProperties().get("category").equals("control");
    }

    private Node findRoot() {
        Node minNode = null;
        Node alternativeRoot = null;

        for (Node node : nodes) {
            InputNode inputNode = node.inputNode;
            String s = inputNode.getProperties().get("name");
            if (s != null && s.equals("Root")) {
                return node;
            }

            if (alternativeRoot == null && node.preds.isEmpty()) {
                alternativeRoot = node;
            }

            if (minNode == null || node.inputNode.getId() < minNode.inputNode.getId()) {
                minNode = node;
            }
        }

        if (alternativeRoot != null) {
            return alternativeRoot;
        } else {
            return minNode;
        }
    }

    private Set<Node> reachableNodes() {
        Node root = findRoot();
        if(root == null) {
            assert false : "No root found!";
            return null;
        }
        // Mark all nodes reachable in backward traversal from root
        Set<Node> reachable = new HashSet<>();
        reachable.add(root);
        Stack<Node> stack = new Stack<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node cur = stack.pop();
            for (Node n : cur.preds) {
                if (!reachable.contains(n)) {
                    reachable.add(n);
                    stack.push(n);
                }
            }
        }
        return reachable;
    }

    public boolean hasCategoryInformation() {
        for (InputNode n : graph.getNodes()) {
            if (n.getProperties().get("category") == null) {
                return false;
            }
        }
        return true;
    }

    public void buildUpGraph() {

        for (InputNode n : graph.getNodes()) {
            Node node = new Node();
            node.inputNode = n;
            nodes.add(node);
            String p = n.getProperties().get("is_block_proj");
            node.isBlockProjection = (p != null && p.equals("true"));
            p = n.getProperties().get("is_block_start");
            node.isBlockStart = (p != null && p.equals("true"));
            inputNodeToNode.put(n, node);
        }

        Map<Integer, List<InputEdge>> edgeMap = new HashMap<>(graph.getEdges().size());
        for (InputEdge e : graph.getEdges()) {

            int to = e.getTo();
            if (!edgeMap.containsKey(to)) {
                edgeMap.put(to, new ArrayList<InputEdge>());
            }


            List<InputEdge> list = edgeMap.get(to);
            list.add(e);
        }


        for (Integer i : edgeMap.keySet()) {

            List<InputEdge> list = edgeMap.get(i);
            list.sort(edgeComparator);

            int to = i;
            InputNode toInputNode = graph.getNode(to);
            Node toNode = inputNodeToNode.get(toInputNode);
            for (InputEdge e : list) {
                assert to == e.getTo();
                int from = e.getFrom();
                InputNode fromInputNode = graph.getNode(from);
                Node fromNode = inputNodeToNode.get(fromInputNode);
                fromNode.succs.add(toNode);
                toNode.preds.add(fromNode);
                toNode.predIndices.add(e.getToIndex());
            }
        }
    }

    // Mark nodes that form the CFG (same as shown by the 'Show control flow
    // only' filter, plus the Root node).
    public void markCFGNodes() {
        for (Node n : nodes) {
            String category = n.inputNode.getProperties().get("category");
            if (category.equals("control") || category.equals("mixed")) {
                // Example: If, IfTrue, CallStaticJava.
                n.isCFG = true;
            } else if (n.inputNode.getProperties().get("type").equals("bottom")
                       && n.preds.size() > 0 &&
                       n.preds.get(0) != null &&
                       n.preds.get(0).inputNode.getProperties()
                       .get("category").equals("control")) {
                // Example: Halt, Return, Rethrow.
                n.isCFG = true;
            } else if (n.isBlockStart || n.isBlockProjection) {
                // Example: Root.
                n.isCFG = true;
            } else {
                n.isCFG = false;
            }
        }
    }

    // Fix ill-formed graphs with orphan/widow control-flow nodes by adding
    // edges from/to the Root node. Such edges are assumed by different parts of
    // the scheduling algorithm, but are not always present, e.g. for certain
    // 'Safepoint' nodes in the 'Before RemoveUseless' phase.
    public void connectOrphansAndWidows() {
        Node root = findRoot();
        if (root == null) {
            return;
        }
        for (Node n : nodes) {
            if (n.isCFG) {
                boolean orphan = true;
                for (Node p : n.preds) {
                    if (p != n && p.isCFG) {
                        orphan = false;
                    }
                }
                if (orphan) {
                    // Add edge from root to this node.
                    root.succs.add(n);
                    n.preds.add(0, root);
                }
                boolean widow = true;
                for (Node s : n.succs) {
                    if (s != n && s.isCFG) {
                        widow = false;
                    }
                }
                if (widow) {
                    // Add edge from this node to root.
                    root.preds.add(n);
                    n.succs.add(root);
                }
            }
        }
    }

    // Check invariants in the input graph and in the output schedule. Warn the
    // user rather than crashing, for robustness (an inaccuracy in the schedule
    // approximation should not disable all other IGV functionality).
    public void check() {

        Set<Node> reachable = reachableNodes();
        for (Node n : nodes) {

            // Check that region nodes are well-formed.
            if (isRegion(n) && !n.isBlockStart) {
                ErrorManager.getDefault().log(ErrorManager.WARNING,
                    n + " is not marked with is_block_start, " +
                    "this might affect the quality of the approximated schedule.");
            }

            // Check that phi nodes are well-formed. If they are, check that
            // their inputs are scheduled above their source nodes.
            if (isPhi(n)) {
                if (!reachable.contains(n)) { // Dead phi.
                    continue;
                }
                for (int i = 1; i < n.preds.size(); i++) {
                    Node in = n.preds.get(i);
                    if (in.isCFG) {
                        // This can happen for nodes misclassified as CFG,
                        // for example x64's 'rep_stos'.
                        ErrorManager.getDefault().log(ErrorManager.WARNING,
                            "CFG node " + in + " is input to " + n + ", " +
                            "this might affect the quality of the approximated schedule.");
                        continue;
                    }
                    for (InputBlock b : sourceBlocks(in, n)) {
                        if (!dominates(graph.getBlock(in.inputNode), b)) {
                            ErrorManager.getDefault().log(ErrorManager.WARNING,
                                "inaccurate schedule: " + in + " does not dominate " + b + ".");
                        }
                    }
                }
            }
        }

    }

}
