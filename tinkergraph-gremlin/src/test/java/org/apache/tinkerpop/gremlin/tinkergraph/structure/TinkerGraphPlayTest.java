/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.tinkergraph.structure;

import org.apache.tinkerpop.gremlin.process.traversal.Operator;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Scope;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.graphml.GraphMLIo;
import org.apache.tinkerpop.gremlin.util.TimeUtil;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.apache.tinkerpop.gremlin.process.traversal.P.lt;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.as;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.both;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.choose;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.count;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.fold;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.has;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.in;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.out;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.outE;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.select;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.union;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.valueMap;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.values;

/**
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class TinkerGraphPlayTest {
    private static final Logger logger = LoggerFactory.getLogger(TinkerGraphPlayTest.class);

    @Test
    @Ignore
    public void testPlay8() throws Exception {
        Graph graph = TinkerFactory.createModern();
        GraphTraversalSource g = graph.traversal().withComputer();//GraphTraversalSource.computer());
        //System.out.println(g.V().outE("knows").identity().inV().count().is(P.eq(5)).explain());
        //System.out.println(g.V().hasLabel("person").fold().order(Scope.local).by("age").toList());
        //System.out.println(g.V().repeat(out()).times(2).profile("m").explain());
        final Traversal<?, ?> traversal = g.V().hasLabel("person").<Number>project("a", "b").by(__.outE().count()).by("age");
        System.out.println(traversal.explain());
        //System.out.println(g.V().hasLabel("person").pageRank().by("rank").by(bothE()).values("rank").profile("m").explain());
        //System.out.println(traversal.asAdmin().clone().toString());
        // final Traversal<?,?> clone = traversal.asAdmin().clone();
        // clone.asAdmin().applyStrategies();
        // System.out.println(clone);
        System.out.println(traversal.asAdmin().toList());
        //System.out.println(traversal.asAdmin().getSideEffects().get("m") + " ");
        //System.out.println(g.V().pageRank().order().by(PageRankVertexProgram.PAGE_RANK).valueMap().toList());
    }

    @Test
    @Ignore
    public void benchmarkGroup() throws Exception {
        Graph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();
        graph.io(GraphMLIo.build()).readGraph("../data/grateful-dead.xml");
        /////////

        //g.V().group().by(T.label).by(values("name")).forEachRemaining(x -> logger.info(x.toString()));

        System.out.println("group: " + g.V().both("followedBy").both("followedBy").group().by("songType").by(count()).next());
        System.out.println("groupV3d0: " + g.V().both("followedBy").both("followedBy").groupV3d0().by("songType").by().by(__.count(Scope.local)).next());

        //
        System.out.println("\n\nBig Values -- by(songType)");

        System.out.println("group: " + TimeUtil.clock(10, () -> g.V().both("followedBy").both("followedBy").group().by("songType").by(count()).next()));
        System.out.println("groupV3d0: " + TimeUtil.clock(10, () -> g.V().both("followedBy").both("followedBy").groupV3d0().by("songType").by().by(__.count(Scope.local)).next()) + "\n");

        ///

        System.out.println("group: " + TimeUtil.clock(10, () -> g.V().both("followedBy").both("followedBy").group().by("songType").by(fold()).next()));
        System.out.println("groupV3d0: " + TimeUtil.clock(10, () -> g.V().both("followedBy").both("followedBy").groupV3d0().by("songType").by().next()));

        ///
        System.out.println("\n\nBig Keys -- by(name)");

        System.out.println("group: " + TimeUtil.clock(10, () -> g.V().both("followedBy").both("followedBy").group().by("name").by(count()).next()));
        System.out.println("groupV3d0: " + TimeUtil.clock(10, () -> g.V().both("followedBy").both("followedBy").groupV3d0().by("name").by().by(__.count(Scope.local)).next()) + "\n");

        ///

        System.out.println("group: " + TimeUtil.clock(10, () -> g.V().both("followedBy").both("followedBy").group().by("name").by(fold()).next()));
        System.out.println("groupV3d0: " + TimeUtil.clock(10, () -> g.V().both("followedBy").both("followedBy").groupV3d0().by("name").by().next()));

    }

    @Test
    @Ignore
    public void testPlay() {
        Graph g = TinkerGraph.open();
        Vertex v1 = g.addVertex(T.id, "1", "animal", "males");
        Vertex v2 = g.addVertex(T.id, "2", "animal", "puppy");
        Vertex v3 = g.addVertex(T.id, "3", "animal", "mama");
        Vertex v4 = g.addVertex(T.id, "4", "animal", "puppy");
        Vertex v5 = g.addVertex(T.id, "5", "animal", "chelsea");
        Vertex v6 = g.addVertex(T.id, "6", "animal", "low");
        Vertex v7 = g.addVertex(T.id, "7", "animal", "mama");
        Vertex v8 = g.addVertex(T.id, "8", "animal", "puppy");
        Vertex v9 = g.addVertex(T.id, "9", "animal", "chula");

        v1.addEdge("link", v2, "weight", 2f);
        v2.addEdge("link", v3, "weight", 3f);
        v2.addEdge("link", v4, "weight", 4f);
        v2.addEdge("link", v5, "weight", 5f);
        v3.addEdge("link", v6, "weight", 1f);
        v4.addEdge("link", v6, "weight", 2f);
        v5.addEdge("link", v6, "weight", 3f);
        v6.addEdge("link", v7, "weight", 2f);
        v6.addEdge("link", v8, "weight", 3f);
        v7.addEdge("link", v9, "weight", 1f);
        v8.addEdge("link", v9, "weight", 7f);

        g.traversal().withSack(Float.MIN_VALUE).V(v1).repeat(outE().sack(Operator.max, "weight").inV()).times(5).sack().forEachRemaining(x -> logger.info(x.toString()));
    }

   /* @Test
    public void testTraversalDSL() throws Exception {
        Graph g = TinkerFactory.createClassic();
        assertEquals(2, g.of(TinkerFactory.SocialTraversal.class).people("marko").knows().name().toList().size());
        g.of(TinkerFactory.SocialTraversal.class).people("marko").knows().name().forEachRemaining(name -> assertTrue(name.equals("josh") || name.equals("vadas")));
        assertEquals(1, g.of(TinkerFactory.SocialTraversal.class).people("marko").created().name().toList().size());
        g.of(TinkerFactory.SocialTraversal.class).people("marko").created().name().forEachRemaining(name -> assertEquals("lop", name));
    }*/

    @Test
    @Ignore
    public void benchmarkStandardTraversals() throws Exception {
        Graph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();
        graph.io(GraphMLIo.build()).readGraph("data/grateful-dead.xml");
        final List<Supplier<Traversal>> traversals = Arrays.asList(
                () -> g.V().outE().inV().outE().inV().outE().inV(),
                () -> g.V().out().out().out(),
                () -> g.V().out().out().out().path(),
                () -> g.V().repeat(out()).times(2),
                () -> g.V().repeat(out()).times(3),
                () -> g.V().local(out().out().values("name").fold()),
                () -> g.V().out().local(out().out().values("name").fold()),
                () -> g.V().out().map(v -> g.V(v.get()).out().out().values("name").toList())
        );
        traversals.forEach(traversal -> {
            logger.info("\nTESTING: {}", traversal.get());
            for (int i = 0; i < 7; i++) {
                final long t = System.currentTimeMillis();
                traversal.get().iterate();
                System.out.print("   " + (System.currentTimeMillis() - t));
            }
        });
    }

    @Test
    @Ignore
    public void testPlay4() throws Exception {
        Graph graph = TinkerGraph.open();
        graph.io(GraphMLIo.build()).readGraph("/Users/marko/software/tinkerpop/tinkerpop3/data/grateful-dead.xml");
        GraphTraversalSource g = graph.traversal();
        final List<Supplier<Traversal>> traversals = Arrays.asList(
                () -> g.V().has(T.label, "song").out().groupCount().<Vertex>by(t ->
                        g.V(t).choose(r -> g.V(r).has(T.label, "artist").hasNext(),
                                in("writtenBy", "sungBy"),
                                both("followedBy")).values("name").next()).fold(),
                () -> g.V().has(T.label, "song").out().groupCount().<Vertex>by(t ->
                        g.V(t).choose(has(T.label, "artist"),
                                in("writtenBy", "sungBy"),
                                both("followedBy")).values("name").next()).fold(),
                () -> g.V().has(T.label, "song").out().groupCount().by(
                        choose(has(T.label, "artist"),
                                in("writtenBy", "sungBy"),
                                both("followedBy")).values("name")).fold(),
                () -> g.V().has(T.label, "song").both().groupCount().<Vertex>by(t -> g.V(t).both().values("name").next()),
                () -> g.V().has(T.label, "song").both().groupCount().by(both().values("name")));
        traversals.forEach(traversal -> {
            logger.info("\nTESTING: {}", traversal.get());
            for (int i = 0; i < 10; i++) {
                final long t = System.currentTimeMillis();
                traversal.get().iterate();
                //System.out.println(traversal.get().toList());
                System.out.print("   " + (System.currentTimeMillis() - t));
            }
        });
    }

    @Test
    @Ignore
    public void testPlayDK() throws Exception {

        Graph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();
        graph.io(GraphMLIo.build()).readGraph("/projects/apache/incubator-tinkerpop/data/grateful-dead.xml");
        System.out.println(g.V().filter(outE("sungBy").count().is(0)).explain());
        System.out.println(g.V().filter(outE("sungBy").count().is(lt(1))).explain());
        System.out.println(g.V().filter(outE("sungBy").count().is(1)).explain());
    }

    @Test
    @Ignore
    public void testPlay7() throws Exception {
        /*TinkerGraph graph = TinkerGraph.open();
        graph.createIndex("name",Vertex.class);
        graph.io(GraphMLIo.build()).readGraph("/Users/marko/software/tinkerpop/tinkerpop3/data/grateful-dead.xml");*/
        //System.out.println(g.V().properties().key().groupCount().next());
        TinkerGraph graph = TinkerFactory.createModern();
        GraphTraversalSource g = graph.traversal();
        final List<Supplier<GraphTraversal<?, ?>>> traversals = Arrays.asList(
                () -> g.V().out().as("v").match(
                        __.as("v").outE().count().as("outDegree"),
                        __.as("v").inE().count().as("inDegree")).select("v", "outDegree", "inDegree").by(valueMap()).by().by().local(union(select("v"), select("inDegree", "outDegree")).unfold().fold())
        );

        traversals.forEach(traversal -> {
            logger.info("pre-strategy:  {}", traversal.get());
            logger.info("post-strategy: {}", traversal.get().iterate());
            logger.info(TimeUtil.clockWithResult(50, () -> traversal.get().toList()).toString());
        });
    }

    @Test
    @Ignore
    public void testPlay5() throws Exception {

        TinkerGraph graph = TinkerGraph.open();
        graph.createIndex("name", Vertex.class);
        graph.io(GraphMLIo.build()).readGraph("/Users/marko/software/tinkerpop/tinkerpop3/data/grateful-dead.xml");
        GraphTraversalSource g = graph.traversal();

        final Supplier<Traversal<?, ?>> traversal = () ->
                g.V().match(
                        as("a").has("name", "Garcia"),
                        as("a").in("writtenBy").as("b"),
                        as("b").out("followedBy").as("c"),
                        as("c").out("writtenBy").as("d"),
                        as("d").where(P.neq("a"))).select("a", "b", "c", "d").by("name");


        logger.info(traversal.get().toString());
        logger.info(traversal.get().iterate().toString());
        traversal.get().forEachRemaining(x -> logger.info(x.toString()));

    }

    @Test
    @Ignore
    public void testPlay6() throws Exception {
        final Graph graph = TinkerGraph.open();
        final GraphTraversalSource g = graph.traversal();
        for (int i = 0; i < 1000; i++) {
            graph.addVertex(T.label, "person", T.id, i);
        }
        graph.vertices().forEachRemaining(a -> {
            graph.vertices().forEachRemaining(b -> {
                if (a != b) {
                    a.addEdge("knows", b);
                }
            });
        });
        graph.vertices(50).next().addEdge("uncle", graph.vertices(70).next());
        logger.info(TimeUtil.clockWithResult(500, () -> g.V().match(as("a").out("knows").as("b"), as("a").out("uncle").as("b")).toList()).toString());
    }
}
