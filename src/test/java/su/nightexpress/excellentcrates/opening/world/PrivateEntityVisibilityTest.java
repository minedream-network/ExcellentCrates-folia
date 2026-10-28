package su.nightexpress.excellentcrates.opening.world;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivateEntityVisibilityTest {

    @Test
    void onlyAllowsTheRegisteredViewerToTrackPrivateEntities() {
        PrivateEntityVisibility visibility = new PrivateEntityVisibility();
        UUID entityId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        visibility.register(entityId, ownerId);

        assertTrue(visibility.canTrack(entityId, ownerId));
        assertFalse(visibility.canTrack(entityId, UUID.randomUUID()));

        visibility.unregister(entityId);
        assertTrue(visibility.canTrack(entityId, UUID.randomUUID()));
    }
}
