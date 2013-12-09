package com.collabinate.server.engine;

import java.util.List;

import com.collabinate.server.StreamEntry;
import com.collabinate.server.activitystreams.ActivityStreamsObject;

/**
 * The interface for a Collabinate server that performs read (retrieval)
 * operations.
 * 
 * @author mafuba
 *
 */
public interface CollabinateReader
{
	/**
	 * Retrieves a collection of entries for an entity, with paging ability. 
	 * 
	 * @param tenantId the tenant for which the request is processed.
	 * @param entityId The ID of the entity for which to retrieve a stream.
	 * @param startIndex The zero-based index of the first element to retrieve.
	 * @param entriesToReturn The maximum number of stream entries to retrieve.
	 * @return A collection of stream entries for the given entity.
	 */
	public List<StreamEntry> getStream(String tenantId, String entityId,
			long startIndex, int entriesToReturn);
	
	/**
	 * Retrieves a collection of stream entries for the entities that a user
	 * follows, in chronological order.
	 * 
	 * @param tenantId the tenant for which the request is processed.
	 * @param userId The ID of the user for which to retrieve a feed.
	 * @param startIndex The zero-based index of the first element to retrieve.
	 * @param entriesToReturn The maximum number of feed entries to retrieve.
	 * @return A collection of feed entries for the given user.
	 */
	public List<StreamEntry> getFeed(String tenantId, String userId,
			long startIndex, int entriesToReturn);
	
	/**
	 * Retrieves a boolean value for whether a user is following an entity.
	 * 
	 * @param tenantId the tenant for which the request is processed.
	 * @param userId The ID of the user for which to determine if an entity is
	 * followed
	 * @param entityId The ID of the entity to check if the user is following.
	 * @return True if the given user follows the given entity, otherwise false.
	 */
	public Boolean isUserFollowingEntity(String tenantId, String userId,
			String entityId);
	
	/**
	 * Retrieves the collection of entities followed by a user.
	 * 
	 * @param tenantId the tenant for which the request is processed.
	 * @param userId the ID of the user for which to retrieve the followed
	 * entities.
	 * @return A collection of entities followed by the given user.
	 */
	public List<ActivityStreamsObject> getFollowing(String tenantId,
			String userId);
	
	/**
	 * Retrieves the collection of users that follow an entity.
	 * 
	 * @param tenantId the tenant for which the request is processed.
	 * @param entityId the ID of the entity for which the followers will be
	 * retrieved.
	 * @return A collection of users following the given entity.
	 */
	public List<ActivityStreamsObject> getFollowers(String tenantId,
			String entityId);
}
