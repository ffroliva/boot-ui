package io.github.jdubois.bootui.quarkus.mongodb;

import io.quarkus.arc.Arc;
import io.quarkus.runtime.annotations.Recorder;
import java.util.List;

@Recorder
public class MongoDbClientsRecorder {
    public void install(List<MongoDbClientsSnapshot.Declaration> declarations, boolean truncated) {
        // This creates only BootUI's metadata holder, never a client bean or its contextual instance.
        Arc.container().instance(MongoDbClientsSnapshot.class).get().install(declarations, truncated);
    }
}
