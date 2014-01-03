package com.tinkerpop.gremlin.computer.gremlin;

import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Property;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.computer.GraphMemory;
import com.tinkerpop.blueprints.computer.MessageType;
import com.tinkerpop.blueprints.computer.Messenger;
import com.tinkerpop.blueprints.computer.VertexProgram;
import com.tinkerpop.blueprints.util.StreamFactory;
import com.tinkerpop.gremlin.pipes.Gremlin;
import com.tinkerpop.gremlin.pipes.Pipe;
import com.tinkerpop.gremlin.pipes.util.Holder;
import com.tinkerpop.gremlin.pipes.util.MapHelper;
import com.tinkerpop.gremlin.pipes.util.Path;
import com.tinkerpop.gremlin.pipes.util.SingleIterator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class GremlinVertexProgram implements VertexProgram<GremlinMessage> {

    private MessageType.Global global = MessageType.Global.of(GREMLIN_MESSAGE);

    private static final String GREMLIN_MESSAGE = "gremlinMessage";
    private static final String GREMLIN_PIPELINE = "gremlinPipeline";
    public static final String GRAPH_GREMLINS = "graphGremlins";
    public static final String OBJECT_GREMLINS = "objectGremlins";
    private final Supplier<Gremlin> gremlin;

    public GremlinVertexProgram(final Supplier<Gremlin> gremlin) {
        this.gremlin = gremlin;
    }

    public void setup(final GraphMemory graphMemory) {
        graphMemory.setIfAbsent(GREMLIN_PIPELINE, this.gremlin);
    }

    public void execute(final Vertex vertex, final Messenger<GremlinMessage> messenger, final GraphMemory graphMemory) {

        if (graphMemory.isInitialIteration()) {
            messenger.sendMessage(vertex, MessageType.Global.of(GREMLIN_MESSAGE, vertex), GremlinMessage.of(vertex, new Path(Pipe.NONE, vertex)));
        } else {
            final Map<Object, List<Path>> previousObjectCounters = vertex.<HashMap<Object, List<Path>>>getProperty(OBJECT_GREMLINS).orElse(new HashMap<>());
            final Map<Object, List<Path>> graphCounters = new HashMap<>();
            final Map<Object, List<Path>> objectCounters = new HashMap<>();
            final Set<Object> starts = new HashSet<>();
            final Pipe pipe = getCurrentPipe(graphMemory);

            // RECEIVE MESSAGES
            messenger.receiveMessages(vertex, this.global).forEach(m -> {
                if (m.destination.equals(GremlinMessage.Destination.VERTEX)) {
                    starts.add(new Holder<>(vertex, m.getPath()));
                    MapHelper.incr(graphCounters, vertex, m.getPath());
                } else if (m.destination.equals(GremlinMessage.Destination.EDGE)) {
                    this.getEdge(vertex, m).ifPresent(edge -> {
                        starts.add(new Holder<>(edge, m.getPath()));
                        MapHelper.incr(graphCounters, edge, m.getPath());
                    });
                } else if (m.destination.equals(GremlinMessage.Destination.PROPERTY)) {
                    this.getProperty(vertex, m).ifPresent(property -> {
                        starts.add(new Holder<>(property, m.getPath()));
                        MapHelper.incr(graphCounters, property, m.getPath());
                    });
                } else {
                    throw new UnsupportedOperationException("The provided message can not be processed: " + m);
                }
            });
            // process local object messages
            previousObjectCounters.forEach((a, b) -> {
                b.forEach(path -> {
                    starts.add(new Holder<>(a, path));
                });

            });


            // EXECUTE PIPELINE WITH LOCAL STARTS AND SEND MESSAGES TO ENDS
            starts.forEach(start -> {
                pipe.addStarts(new SingleIterator<>(start));
                pipe.forEachRemaining(h -> {
                    Holder holder = (Holder) h;
                    Object end = holder.get();
                    // System.out.println(start + "-->" + end + " [" + counters.get(start) + "]");
                    if (end instanceof Element || end instanceof Property) {
                        messenger.sendMessage(
                                vertex,
                                MessageType.Global.of(GREMLIN_MESSAGE, Messenger.getHostingVertices(end)),
                                GremlinMessage.of(end, holder.getPath()));
                    } else {
                        if (graphCounters.containsKey(((Holder)start).get()))
                            MapHelper.incr(objectCounters, end, holder.getPath());
                        else if (previousObjectCounters.containsKey(((Holder)start).get()))
                            MapHelper.incr(objectCounters, end, holder.getPath());
                        else
                            throw new IllegalStateException("The provided start does not have a recorded history: " + start);
                    }
                });
            });

            // UPDATE LOCAL STARTS WITH COUNTS
            graphCounters.forEach((k, v) -> {
                if (k instanceof Element) {
                    ((Element) k).setProperty(GRAPH_GREMLINS, v);
                } else if (k instanceof Property) {
                    ((Property) k).setAnnotation(GRAPH_GREMLINS, v);
                }
            });
            if (objectCounters.size() > 0)
                vertex.setProperty(OBJECT_GREMLINS, objectCounters);
        }
    }

    private Optional<Edge> getEdge(final Vertex vertex, final GremlinMessage message) {
        // TODO: WHY IS THIS NOT LIKE FAUNUS WITH A BOTH?
        return StreamFactory.stream(vertex.query().direction(Direction.OUT).edges())
                .filter(e -> e.getId().equals(message.elementId))
                .findFirst();
    }

    private Optional<Property> getProperty(final Vertex vertex, final GremlinMessage message) {
        if (message.elementId.equals(vertex.getId())) {
            final Property property = vertex.getProperty(message.propertyKey);
            return property.isPresent() ? Optional.of(property) : Optional.empty();
        } else {
            // TODO: WHY IS THIS NOT LIKE FAUNUS WITH A BOTH?
            return (Optional) StreamFactory.stream(vertex.query().direction(Direction.OUT).edges())
                    .filter(e -> e.getId().equals(message.elementId))
                    .map(e -> e.getProperty(message.propertyKey))
                    .findFirst();
        }
    }

    private Pipe getCurrentPipe(final GraphMemory graphMemory) {
        final Supplier<Gremlin> gremlin = graphMemory.get(GREMLIN_PIPELINE);
        return (Pipe) gremlin.get().getPipes().get(graphMemory.getIteration());
    }

    ////////// GRAPH COMPUTER METHODS

    public boolean terminate(final GraphMemory graphMemory) {
        Supplier<Gremlin> gremlin = graphMemory.get(GREMLIN_PIPELINE);
        return !(graphMemory.getIteration() < gremlin.get().getPipes().size());
    }

    public Map<String, KeyType> getComputeKeys() {
        return VertexProgram.ofComputeKeys(
                GRAPH_GREMLINS, KeyType.VARIABLE,
                OBJECT_GREMLINS, KeyType.VARIABLE);
    }

    public static Builder create() {
        return new Builder();
    }

    public static class Builder {
        private Supplier<Gremlin> gremlin;

        public Builder gremlin(final Supplier<Gremlin> gremlin) {
            this.gremlin = gremlin;
            return this;
        }

        public GremlinVertexProgram build() {
            return new GremlinVertexProgram(this.gremlin);
        }
    }
}