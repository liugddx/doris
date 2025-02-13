// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.jobs.joinorder.hypergraph;

import org.apache.doris.common.Pair;
import org.apache.doris.nereids.jobs.joinorder.hypergraph.receiver.Counter;
import org.apache.doris.nereids.trees.expressions.Alias;
import org.apache.doris.nereids.trees.expressions.EqualTo;
import org.apache.doris.nereids.trees.plans.JoinType;
import org.apache.doris.nereids.trees.plans.logical.LogicalOlapScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalPlan;
import org.apache.doris.nereids.util.HyperGraphBuilder;
import org.apache.doris.nereids.util.LogicalPlanBuilder;
import org.apache.doris.nereids.util.PlanConstructor;
import org.apache.doris.statistics.Statistics;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import org.apache.hadoop.util.Lists;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

class GraphSimplifierTest {
    private static final LogicalOlapScan scan1 = PlanConstructor.newLogicalOlapScan(0, "t1", 0);
    private static final LogicalOlapScan scan2 = PlanConstructor.newLogicalOlapScan(1, "t2", 0);
    private static final LogicalOlapScan scan3 = PlanConstructor.newLogicalOlapScan(2, "t3", 0);

    @Test
    void testComplexProject() {
        Alias alias1 = new Alias(scan1.getOutput().get(0), "p1");
        LogicalPlan project1 = new LogicalPlanBuilder(scan1)
                .projectExprs(Lists.newArrayList(alias1)).build();
        Alias alias2 = new Alias(scan2.getOutput().get(0), "p2");
        LogicalPlan project2 = new LogicalPlanBuilder(scan2)
                .projectExprs(Lists.newArrayList(alias2)).build();
        Alias alias3 = new Alias(scan3.getOutput().get(0), "p3");
        LogicalPlan project3 = new LogicalPlanBuilder(scan3)
                .projectExprs(Lists.newArrayList(alias3)).build();
        LogicalPlan join = new LogicalPlanBuilder(project1)
                .join(project2, JoinType.INNER_JOIN, Lists.newArrayList(new EqualTo(alias1.toSlot(), alias2.toSlot())), new ArrayList<>())
                .join(project3, JoinType.INNER_JOIN, Lists.newArrayList(new EqualTo(alias2.toSlot(), alias3.toSlot())), new ArrayList<>())
                .build();
        HyperGraph hyperGraph = HyperGraphBuilder.buildHyperGraphFromPlan(join);
        for (Node node : hyperGraph.getNodes()) {
            node.getGroup().setStatistics(new Statistics(1, new HashMap<>()));
        }
        GraphSimplifier graphSimplifier = new GraphSimplifier(hyperGraph);
        while (graphSimplifier.applySimplificationStep()) {
        }
        Assertions.assertNull(graphSimplifier.getLastAppliedSteps());
    }

    @Test
    void testStarQuery() {
        //      t1
        //      |
        //t3-- t0 -- t4
        //      |
        //     t2
        HyperGraph hyperGraph = new HyperGraphBuilder()
                .init(10, 20, 30, 40, 50)
                .addEdge(JoinType.INNER_JOIN, 0, 1)
                .addEdge(JoinType.INNER_JOIN, 0, 2)
                .addEdge(JoinType.INNER_JOIN, 0, 3)
                .addEdge(JoinType.INNER_JOIN, 0, 4)
                .build();
        GraphSimplifier graphSimplifier = new GraphSimplifier(hyperGraph);
        List<Pair<Long, Long>> steps = ImmutableList.<Pair<Long, Long>>builder()
                .add(Pair.of(17L, 2L))   // 04   - 1
                .add(Pair.of(17L, 4L))   // 04   - 2
                .add(Pair.of(17L, 8L))   // 04   - 3
                .add(Pair.of(25L, 2L))   // 034  - 1
                .add(Pair.of(25L, 4L))   // 034  - 2
                .add(Pair.of(29L, 2L))   // 0234 - 1
                .build(); // 0-4-3-2-1 : big left deep tree
        for (Pair<Long, Long> step : steps) {
            if (!graphSimplifier.applySimplificationStep()) {
                break;
            }
            System.out.println(graphSimplifier.getLastAppliedSteps());
            Assertions.assertEquals(step, graphSimplifier.getLastAppliedSteps());
        }
        Counter counter = new Counter();
        SubgraphEnumerator subgraphEnumerator = new SubgraphEnumerator(counter, hyperGraph);
        subgraphEnumerator.enumerate();
        for (int count : counter.getAllCount().values()) {
            Assertions.assertTrue(count < 10);
        }
        Assertions.assertTrue(graphSimplifier.isTotalOrder());
    }

    @Test
    void testCircleGraph() {
        //    .--t0\
        //   /    | \
        //   |   t1  t3
        //   \    | /
        //    `--t2/
        HyperGraph hyperGraph = new HyperGraphBuilder()
                .init(10, 20, 30, 40)
                .addEdge(JoinType.INNER_JOIN, 0, 1)
                .addEdge(JoinType.INNER_JOIN, 0, 2)
                .addEdge(JoinType.INNER_JOIN, 0, 3)
                .addEdge(JoinType.INNER_JOIN, 1, 2)
                .addEdge(JoinType.INNER_JOIN, 2, 3)
                .build();
        GraphSimplifier graphSimplifier = new GraphSimplifier(hyperGraph);
        while (graphSimplifier.applySimplificationStep()) {
        }
        Counter counter = new Counter();
        SubgraphEnumerator subgraphEnumerator = new SubgraphEnumerator(counter, hyperGraph);
        subgraphEnumerator.enumerate();
        for (int count : counter.getAllCount().values()) {
            Assertions.assertTrue(count < 10);
        }
        Assertions.assertTrue(graphSimplifier.isTotalOrder());
    }

    @Test
    void testClique() {
        //    .--t0\
        //   /    | \
        //   |   t1- t3
        //   \    | /
        //    `--t2/
        HyperGraph hyperGraph = new HyperGraphBuilder()
                .init(10, 20, 30, 40)
                .addEdge(JoinType.INNER_JOIN, 0, 1)
                .addEdge(JoinType.INNER_JOIN, 0, 2)
                .addEdge(JoinType.INNER_JOIN, 0, 3)
                .addEdge(JoinType.INNER_JOIN, 1, 2)
                .addEdge(JoinType.INNER_JOIN, 1, 3)
                .addEdge(JoinType.INNER_JOIN, 2, 3)
                .build();
        GraphSimplifier graphSimplifier = new GraphSimplifier(hyperGraph);
        while (graphSimplifier.applySimplificationStep()) {
        }
        Counter counter = new Counter();
        SubgraphEnumerator subgraphEnumerator = new SubgraphEnumerator(counter, hyperGraph);
        subgraphEnumerator.enumerate();
        for (int count : counter.getAllCount().values()) {
            Assertions.assertTrue(count < 10);
        }
        Assertions.assertTrue(graphSimplifier.isTotalOrder());
    }

    @Test
    void testHugeStar() {
        //  t11 t3 t4 t5 t12
        //    `  \ | / '
        //    t1--t0--t2
        //    '  / | \  `
        //   t9 t6 t7 t8  t10
        HyperGraph hyperGraph = new HyperGraphBuilder()
                .init(10, 20, 30, 40, 50, 70, 60, 80, 90, 100, 110, 120)
                .addEdge(JoinType.INNER_JOIN, 0, 1)
                .addEdge(JoinType.INNER_JOIN, 0, 2)
                .addEdge(JoinType.INNER_JOIN, 0, 3)
                .addEdge(JoinType.INNER_JOIN, 0, 4)
                .addEdge(JoinType.INNER_JOIN, 0, 5)
                .addEdge(JoinType.INNER_JOIN, 0, 6)
                .addEdge(JoinType.INNER_JOIN, 0, 7)
                .addEdge(JoinType.INNER_JOIN, 0, 8)
                .addEdge(JoinType.INNER_JOIN, 0, 9)
                .addEdge(JoinType.INNER_JOIN, 0, 10)
                .addEdge(JoinType.INNER_JOIN, 0, 11)
                .build();
        GraphSimplifier graphSimplifier = new GraphSimplifier(hyperGraph);
        while (graphSimplifier.applySimplificationStep()) {
        }
        Counter counter = new Counter();
        SubgraphEnumerator subgraphEnumerator = new SubgraphEnumerator(counter, hyperGraph);
        subgraphEnumerator.enumerate();
        for (int count : counter.getAllCount().values()) {
            Assertions.assertTrue(count < 10);
        }
        Assertions.assertTrue(graphSimplifier.isTotalOrder());
    }

    @Test
    void testComplexQuery() {
        HyperGraph hyperGraph = new HyperGraphBuilder()
                .init(6, 2, 1, 3, 5, 4)
                .addEdge(JoinType.INNER_JOIN, 3, 4)
                .addEdge(JoinType.INNER_JOIN, 3, 5)
                .addEdge(JoinType.INNER_JOIN, 2, 3)
                .addEdge(JoinType.INNER_JOIN, 2, 5)
                .addEdge(JoinType.INNER_JOIN, 2, 4)
                .addEdge(JoinType.INNER_JOIN, 1, 5)
                .addEdge(JoinType.INNER_JOIN, 1, 4)
                .addEdge(JoinType.INNER_JOIN, 0, 2)
                .build();
        GraphSimplifier graphSimplifier = new GraphSimplifier(hyperGraph);
        while (graphSimplifier.applySimplificationStep()) {
        }
        Counter counter = new Counter();
        SubgraphEnumerator subgraphEnumerator = new SubgraphEnumerator(counter, hyperGraph);
        subgraphEnumerator.enumerate();
        for (int count : counter.getAllCount().values()) {
            Assertions.assertTrue(count < 1000);
        }
        Assertions.assertTrue(graphSimplifier.isTotalOrder());
    }

    @Test
    void testRandomQuery() {
        for (int i = 0; i < 10; i++) {
            HyperGraph hyperGraph = new HyperGraphBuilder().randomBuildWith(6, 6);
            GraphSimplifier graphSimplifier = new GraphSimplifier(hyperGraph);
            while (graphSimplifier.applySimplificationStep()) {
            }
            Counter counter = new Counter();
            SubgraphEnumerator subgraphEnumerator = new SubgraphEnumerator(counter, hyperGraph);
            subgraphEnumerator.enumerate();
        }
    }

    @Test
    void testLimit() {
        int tableNum = 10;
        int edgeNum = 20;
        for (int limit = 1000; limit < 10000; limit += 100) {
            HyperGraph hyperGraph = new HyperGraphBuilder().randomBuildWith(tableNum, edgeNum);
            GraphSimplifier graphSimplifier = new GraphSimplifier(hyperGraph);
            graphSimplifier.simplifyGraph(limit);
            Counter counter = new Counter();
            SubgraphEnumerator subgraphEnumerator = new SubgraphEnumerator(counter, hyperGraph);
            subgraphEnumerator.enumerate();
            Assertions.assertTrue(counter.getLimit() >= 0);
        }
    }

    @Disabled
    @Test
    void benchGraphSimplifier() {
        int tableNum = 64;
        int edgeNum = 64 * 63 / 2;
        int limit = 1000;

        int times = 4;
        double totalTime = 0;
        for (int i = 0; i < times; i++) {
            totalTime += benchGraphSimplifier(tableNum, edgeNum, limit);
        }
        System.out.println(totalTime / times);
    }

    double benchGraphSimplifier(int tableNum, int edgeNum, int limit) {
        HyperGraph hyperGraph = new HyperGraphBuilder(Sets.newHashSet(JoinType.INNER_JOIN))
                .randomBuildWith(tableNum, edgeNum);
        double now = System.currentTimeMillis();
        GraphSimplifier graphSimplifier = new GraphSimplifier(hyperGraph);
        graphSimplifier.simplifyGraph(limit);
        return System.currentTimeMillis() - now;
    }
}
