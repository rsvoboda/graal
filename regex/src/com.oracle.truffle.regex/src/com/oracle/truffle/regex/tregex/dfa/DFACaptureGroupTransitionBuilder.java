/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.regex.tregex.dfa;

import java.util.Arrays;

import com.oracle.truffle.regex.UnsupportedRegexException;
import com.oracle.truffle.regex.charset.CodePointSet;
import com.oracle.truffle.regex.tregex.automaton.StateSet;
import com.oracle.truffle.regex.tregex.automaton.TransitionBuilder;
import com.oracle.truffle.regex.tregex.buffer.ByteArrayBuffer;
import com.oracle.truffle.regex.tregex.buffer.CompilationBuffer;
import com.oracle.truffle.regex.tregex.buffer.ObjectArrayBuffer;
import com.oracle.truffle.regex.tregex.nfa.NFAState;
import com.oracle.truffle.regex.tregex.nfa.NFAStateTransition;
import com.oracle.truffle.regex.tregex.nodes.dfa.DFACaptureGroupLazyTransition;
import com.oracle.truffle.regex.tregex.nodes.dfa.DFACaptureGroupPartialTransition;
import com.oracle.truffle.regex.tregex.util.json.Json;
import com.oracle.truffle.regex.tregex.util.json.JsonConvertible;
import com.oracle.truffle.regex.tregex.util.json.JsonObject;
import com.oracle.truffle.regex.tregex.util.json.JsonValue;

public class DFACaptureGroupTransitionBuilder extends DFAStateTransitionBuilder {

    private final DFAGenerator dfaGen;
    private StateSet<NFAState> requiredStates = null;
    private int[] requiredStatesIndexMap = null;
    private DFACaptureGroupLazyTransition lazyTransition = null;

    DFACaptureGroupTransitionBuilder(CodePointSet matcherBuilder, NFATransitionSet transitions, DFAGenerator dfaGen) {
        super(matcherBuilder, transitions);
        this.dfaGen = dfaGen;
    }

    @Override
    public DFAStateTransitionBuilder createNodeSplitCopy() {
        return new DFACaptureGroupTransitionBuilder(getMatcherBuilder(), getTransitionSet(), dfaGen);
    }

    @Override
    public DFACaptureGroupTransitionBuilder createMerged(TransitionBuilder<NFATransitionSet> other, CodePointSet mergedMatcher) {
        return new DFACaptureGroupTransitionBuilder(mergedMatcher, getTransitionSet().createMerged(other.getTransitionSet()), dfaGen);
    }

    public void setLazyTransition(DFACaptureGroupLazyTransition lazyTransition) {
        this.lazyTransition = lazyTransition;
    }

    /**
     * Returns {@code true} if the DFA executor may safely omit the result set reordering step in
     * this transition.
     *
     * @see DFACaptureGroupPartialTransition
     */
    private boolean skipReorder() {
        return !dfaGen.getProps().isSearching() && getSource().isInitialState();
    }

    private StateSet<NFAState> getRequiredStates() {
        if (requiredStates == null) {
            requiredStates = StateSet.create(dfaGen.getNfa());
            for (NFAStateTransition nfaTransition : getTransitionSet()) {
                requiredStates.add(nfaTransition.getSource());
            }
        }
        return requiredStates;
    }

    private int[] getRequiredStatesIndexMap() {
        if (requiredStatesIndexMap == null) {
            requiredStatesIndexMap = getRequiredStates().toArrayOfIndices();
        }
        return requiredStatesIndexMap;
    }

    private DFACaptureGroupPartialTransition createPartialTransition(StateSet<NFAState> targetStates, int[] targetStatesIndexMap, CompilationBuffer compilationBuffer) {
        int numberOfNFAStates = Math.max(getRequiredStates().size(), targetStates.size());
        PartialTransitionDebugInfo partialTransitionDebugInfo = null;
        if (dfaGen.getEngineOptions().isDumpAutomata()) {
            partialTransitionDebugInfo = new PartialTransitionDebugInfo(numberOfNFAStates);
        }
        dfaGen.updateMaxNumberOfNFAStatesInOneTransition(numberOfNFAStates);
        int[] newOrder = new int[numberOfNFAStates];
        Arrays.fill(newOrder, -1);
        boolean[] used = new boolean[newOrder.length];
        int[] copySource = new int[getRequiredStates().size()];
        ObjectArrayBuffer indexUpdates = compilationBuffer.getObjectBuffer1();
        ObjectArrayBuffer indexClears = compilationBuffer.getObjectBuffer2();
        ByteArrayBuffer arrayCopies = compilationBuffer.getByteArrayBuffer();
        for (NFAStateTransition nfaTransition : getTransitionSet()) {
            if (targetStates.contains(nfaTransition.getTarget())) {
                int sourceIndex = getStateIndex(getRequiredStatesIndexMap(), nfaTransition.getSource());
                int targetIndex = getStateIndex(targetStatesIndexMap, nfaTransition.getTarget());
                if (dfaGen.getEngineOptions().isDumpAutomata()) {
                    partialTransitionDebugInfo.mapResultToNFATransition(targetIndex, nfaTransition);
                }
                assert !(nfaTransition.getTarget().isForwardFinalState()) || targetIndex == DFACaptureGroupPartialTransition.FINAL_STATE_RESULT_INDEX;
                if (!used[sourceIndex]) {
                    used[sourceIndex] = true;
                    newOrder[targetIndex] = sourceIndex;
                    copySource[sourceIndex] = targetIndex;
                } else {
                    arrayCopies.add((byte) copySource[sourceIndex]);
                    arrayCopies.add((byte) targetIndex);
                }
                if (nfaTransition.getGroupBoundaries().hasIndexUpdates()) {
                    indexUpdates.add(nfaTransition.getGroupBoundaries().updatesToPartialTransitionArray(targetIndex));
                }
                if (nfaTransition.getGroupBoundaries().hasIndexClears()) {
                    indexClears.add(nfaTransition.getGroupBoundaries().clearsToPartialTransitionArray(targetIndex));
                }
            }
        }
        int order = 0;
        for (int i = 0; i < newOrder.length; i++) {
            if (newOrder[i] == -1) {
                while (used[order]) {
                    order++;
                }
                newOrder[i] = order++;
            }
        }
        byte preReorderFinalStateResultIndex = (byte) newOrder[DFACaptureGroupPartialTransition.FINAL_STATE_RESULT_INDEX];
        // important: don't change the order, because newOrderToSequenceOfSwaps() reuses
        // CompilationBuffer#getByteArrayBuffer()
        byte[] byteArrayCopies = arrayCopies.length() == 0 ? DFACaptureGroupPartialTransition.EMPTY_ARRAY_COPIES : arrayCopies.toArray();
        byte[] reorderSwaps = skipReorder() ? DFACaptureGroupPartialTransition.EMPTY_REORDER_SWAPS : newOrderToSequenceOfSwaps(newOrder, compilationBuffer);
        DFACaptureGroupPartialTransition dfaCaptureGroupPartialTransitionNode = DFACaptureGroupPartialTransition.create(
                        dfaGen,
                        reorderSwaps,
                        byteArrayCopies,
                        indexUpdates.toArray(DFACaptureGroupPartialTransition.EMPTY_INDEX_UPDATES),
                        indexClears.toArray(DFACaptureGroupPartialTransition.EMPTY_INDEX_CLEARS),
                        preReorderFinalStateResultIndex);
        if (dfaGen.getEngineOptions().isDumpAutomata()) {
            partialTransitionDebugInfo.node = dfaCaptureGroupPartialTransitionNode;
            dfaGen.registerCGPartialTransitionDebugInfo(partialTransitionDebugInfo);
        }
        return dfaCaptureGroupPartialTransitionNode;
    }

    /**
     * Converts the ordering given by {@code newOrder} to a sequence of swap operations as needed by
     * {@link DFACaptureGroupPartialTransition}. The number of swap operations is guaranteed to be
     * smaller than {@code newOrder.length}. Caution: this method uses
     * {@link CompilationBuffer#getByteArrayBuffer()}.
     */
    private static byte[] newOrderToSequenceOfSwaps(int[] newOrder, CompilationBuffer compilationBuffer) {
        ByteArrayBuffer swaps = compilationBuffer.getByteArrayBuffer();
        for (int i = 0; i < newOrder.length; i++) {
            int swapSource = newOrder[i];
            int swapTarget = swapSource;
            if (swapSource == i) {
                continue;
            }
            do {
                swapSource = swapTarget;
                swapTarget = newOrder[swapTarget];
                swaps.add((byte) swapSource);
                swaps.add((byte) swapTarget);
                newOrder[swapSource] = swapSource;
            } while (swapTarget != i);
        }
        assert swaps.length() / 2 < newOrder.length;
        return swaps.length() == 0 ? DFACaptureGroupPartialTransition.EMPTY_REORDER_SWAPS : swaps.toArray();
    }

    public DFACaptureGroupLazyTransition toLazyTransition(CompilationBuffer compilationBuffer) {
        if (lazyTransition == null) {
            DFAStateNodeBuilder successor = getTarget();
            DFACaptureGroupPartialTransition[] partialTransitions = new DFACaptureGroupPartialTransition[successor.getSuccessors().length];
            for (int i = 0; i < successor.getSuccessors().length; i++) {
                DFACaptureGroupTransitionBuilder successorTransition = (DFACaptureGroupTransitionBuilder) successor.getSuccessors()[i];
                partialTransitions[i] = createPartialTransition(successorTransition.getRequiredStates(), successorTransition.getRequiredStatesIndexMap(), compilationBuffer);
            }
            DFACaptureGroupPartialTransition transitionToFinalState = null;
            DFACaptureGroupPartialTransition transitionToAnchoredFinalState = null;
            if (successor.isUnAnchoredFinalState()) {
                NFAState src = successor.getUnAnchoredFinalStateTransition().getSource();
                transitionToFinalState = createPartialTransition(StateSet.create(dfaGen.getNfa(), src), new int[]{src.getId()}, compilationBuffer);
            }
            if (successor.isAnchoredFinalState()) {
                NFAState src = successor.getAnchoredFinalStateTransition().getSource();
                transitionToAnchoredFinalState = createPartialTransition(StateSet.create(dfaGen.getNfa(), src), new int[]{src.getId()}, compilationBuffer);
            }
            assert getId() >= 0;
            if (getId() > Short.MAX_VALUE) {
                throw new UnsupportedRegexException("too many capture group transitions");
            }
            lazyTransition = new DFACaptureGroupLazyTransition((short) getId(), partialTransitions, transitionToFinalState, transitionToAnchoredFinalState);
        }
        return lazyTransition;
    }

    private static int getStateIndex(int[] stateIndexMap, NFAState state) {
        int ret = Arrays.binarySearch(stateIndexMap, state.getId());
        assert ret >= 0;
        return ret;
    }

    public static class PartialTransitionDebugInfo implements JsonConvertible {

        private DFACaptureGroupPartialTransition node;
        private final short[] resultToTransitionMap;

        public PartialTransitionDebugInfo(DFACaptureGroupPartialTransition node) {
            this(node, 0);
        }

        public PartialTransitionDebugInfo(int nResults) {
            this(null, nResults);
        }

        public PartialTransitionDebugInfo(DFACaptureGroupPartialTransition node, int nResults) {
            this.node = node;
            this.resultToTransitionMap = new short[nResults];
        }

        public DFACaptureGroupPartialTransition getNode() {
            return node;
        }

        public void mapResultToNFATransition(int resultNumber, NFAStateTransition transition) {
            resultToTransitionMap[resultNumber] = transition.getId();
        }

        @Override
        public JsonValue toJson() {
            return ((JsonObject) node.toJson()).append(Json.prop("resultToNFATransitionMap", Json.array(resultToTransitionMap)));
        }
    }
}
