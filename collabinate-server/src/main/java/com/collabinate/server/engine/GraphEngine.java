package com.collabinate.server.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.collabinate.server.activitystreams.Activity;
import com.collabinate.server.activitystreams.ActivityStreamsCollection;
import com.collabinate.server.activitystreams.ActivityStreamsObject;
import com.google.common.base.Joiner;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Vertex;

/**
 * An implementation of both reader and writer backed by a graph database.
 * 
 * @author mafuba
 * 
 */
public class GraphEngine implements CollabinateReader, CollabinateWriter
{
	/**
	 * Logger instance.
	 */
	private final Logger logger = LoggerFactory.getLogger(GraphEngine.class);
	
	/**
	 * The graph database backing this instance.
	 */
	private CollabinateGraph graph;
	
	// Comparators
	private ActivityDateComparator activityDateComparator =
			new ActivityDateComparator();
	private EntityFirstActivityDateComparator firstActivityDateComparator =
			new EntityFirstActivityDateComparator();
	
	/**
	 * Ensures that the graph can have IDs assigned.
	 * 
	 * @param graph A Tinkerpop BluePrints graph to act as the store for the
	 * server.
	 */
	public GraphEngine(final CollabinateGraph graph)
	{
		if (null == graph)
		{
			throw new IllegalArgumentException("graph must not be null");
		}
		
		this.graph = graph;
	}
	
	@Override
	public void addActivity(String tenantId, String entityId, Activity activity)
	{
		if (null == tenantId)
		{
			throw new IllegalArgumentException("tenantId must not be null");
		}
		
		if (null == entityId)
		{
			throw new IllegalArgumentException("entityId must not be null");
		}
		
		if (null == activity)
		{
			throw new IllegalArgumentException("activity must not be null");
		}
		
		Vertex entityVertex = getOrCreateEntityVertex(tenantId, entityId);
		Vertex activityVertex = serializeActivity(activity, tenantId, entityId);
		
		// if the inserted activity is first in its stream, it may have
		// changed the entity order for feed paths
		boolean updateOrder = insertActivity(entityVertex, activityVertex);
		adjustNumericProperty(entityVertex, STRING_STREAM_COUNT, 1);
		updateFeed(tenantId, entityId, entityVertex, 1, updateOrder);		
		
		graph.commit();		
	}
	
	@Override
	public Activity getActivity(String tenantId, String entityId,
			String activityId)
	{
		Activity activity = null;
		
		Vertex activityVertex =
				getActivityVertex(tenantId, entityId, activityId);
		
		if (null != activityVertex)
		{
			activity = new Activity(
					(String)activityVertex.getProperty(STRING_CONTENT));
		}
		
		graph.commit();
		
		return activity;
	}
	
	@Override
	public ActivityStreamsObject getComment(String tenantId, String entityId,
			String activityId, String commentId)
	{
		ActivityStreamsObject comment = null;
		
		Vertex commentVertex =
				getCommentVertex(tenantId, entityId, activityId, commentId);
		
		if (null != commentVertex)
		{
			comment = new ActivityStreamsObject(
					(String)commentVertex.getProperty(STRING_CONTENT));
		}
		
		graph.commit();
		
		return comment;
	}
	
	/**
	 * Attempts to retrieve the vertex for the entity with the given ID. If a
	 * matching entity cannot be found, the vertex is created.
	 * 
	 * @param tenantId The ID of the tenant to add to the vertex upon creation.
	 * @param entityId The ID of the entity to add to the vertex upon creation.
	 * @return The vertex for the given entity.
	 */
	private synchronized Vertex getOrCreateEntityVertex(final String tenantId,
			final String entityId)
	{
		Vertex entityVertex = graph.getVertex(tenantId + "/" + entityId);
		if (null == entityVertex)
		{
			entityVertex = graph.addVertex(tenantId + "/" + entityId);
			entityVertex.setProperty(STRING_TENANT_ID, tenantId);
			entityVertex.setProperty(STRING_ENTITY_ID, entityId);
			entityVertex.setProperty(STRING_TYPE, STRING_ENTITY);
			entityVertex.setProperty(STRING_CREATED,
					DateTime.now(DateTimeZone.UTC).toString());
			entityVertex.setProperty(STRING_STREAM_COUNT, 0);
			entityVertex.setProperty(STRING_FEED_COUNT, 0);
			entityVertex.setProperty(STRING_FOLLOWING_COUNT, 0);
			entityVertex.setProperty(STRING_FOLLOWER_COUNT, 0);
			graph.commit();
		}
		return entityVertex;
	}
	
	/**
	 * Retrieves a single activity vertex that matches the given parameters, or
	 * null if none match.
	 * 
	 * @param tenantId the tenant for which the request is processed.
	 * @param entityId the ID of the entity for which to retrieve an activity.
	 * @param activityId the ID of the activity to retrieve.
	 * @return A vertex for the given activity, or null.
	 */
	private Vertex getActivityVertex(String tenantId, String entityId,
			String activityId)
	{
		return graph.getVertex(tenantId + "/" + entityId + "/" + activityId);
	}

	/**
	 * Retrieves a single comment vertex that matches the given parameters, or
	 * null if none match.
	 * 
	 * @param tenantId the tenant for which the request is processed.
	 * @param entityId the ID of the entity for which to retrieve an comment.
	 * @param activityId the ID of the activity for which to retrieve a comment.
	 * @param commentId the ID of the comment to retrieve.
	 * @return A vertex for the given comment, or null.
	 */
	private Vertex getCommentVertex(String tenantId, String entityId,
			String activityId, String commentId)
	{
		return graph.getVertex(tenantId + "/" + entityId + "/" + activityId +
				"/" + commentId);
	}

	/**
	 * Creates a new vertex representation of a given activity.
	 * 
	 * @param activity The activity to be represented.
	 * @param tenantId The tenant for the activity.
	 * @param entityId The entity to which the activity belongs.
	 * @return A vertex that represents the given activity.
	 */
	private Vertex serializeActivity(final Activity activity,
			final String tenantId, final String entityId)
	{
		Vertex activityVertex = graph.addVertex(tenantId + "/" + entityId + "/"
				+ activity.getId());
		activityVertex.setProperty(STRING_TENANT_ID, tenantId);
		activityVertex.setProperty(STRING_ENTITY_ID, entityId);
		activityVertex.setProperty(STRING_ACTIVITY_ID, activity.getId());
		activityVertex.setProperty(STRING_TYPE, STRING_ACTIVITY);
		activityVertex.setProperty(STRING_SORTTIME, 
				activity.getSortTime().toString());
		activityVertex.setProperty(STRING_CREATED,
				DateTime.now(DateTimeZone.UTC).toString());
		activityVertex.setProperty(STRING_CONTENT, activity.toString());
		activityVertex.setProperty(STRING_COMMENT_COUNT, 0);
		activityVertex.setProperty(STRING_LIKE_COUNT, 0);
		return activityVertex;
	}
	
	/**
	 * Creates a new vertex representation of a given comment.
	 * 
	 * @param comment The comment to be represented.
	 * @param tenantId The tenant for the comment.
	 * @param entityId The entity to which the activity for the comment belongs.
	 * @param activityId The activity to which the comment belongs.
	 * @return A vertex that represents the given comment.
	 */
	private Vertex serializeComment(final ActivityStreamsObject comment,
			final String tenantId, final String entityId,
			final String activityId)
	{
		Vertex commentVertex = graph.addVertex(tenantId + "/" + entityId + "/"
				+ activityId + "/" + comment.getId());
		
		commentVertex.setProperty(STRING_TENANT_ID, tenantId);
		commentVertex.setProperty(STRING_ENTITY_ID, entityId);
		commentVertex.setProperty(STRING_ACTIVITY_ID, activityId);
		commentVertex.setProperty(STRING_COMMENT_ID, comment.getId());
		commentVertex.setProperty(STRING_TYPE, STRING_COMMENT);
		commentVertex.setProperty(STRING_SORTTIME, 
				comment.getSortTime().toString());
		commentVertex.setProperty(STRING_CREATED,
				DateTime.now(DateTimeZone.UTC).toString());
		commentVertex.setProperty(STRING_CONTENT, comment.toString());
		
		return commentVertex;
	}
	
	/**
	 * Adds an activity vertex at the correct chronological location among the
	 * stream vertices of an entity.
	 * 
	 * @param entity The vertex representing the entity.
	 * @param addedActivity The activity to add to the stream.
	 * @return true if the added activity is the newest (first) in the stream,
	 * otherwise false.
	 */
	private boolean insertActivity(final Vertex entity,
			final Vertex addedActivity)
	{
		if (null == entity)
		{
			throw new IllegalArgumentException("entity must not be null");
		}
		
		if (null == addedActivity)
		{
			throw new IllegalArgumentException(
					"addedActivity must not be null");
		}
		
		Edge currentStreamEdge = getStreamEdge(entity);
		Vertex currentActivity = getNextActivity(entity);
		Vertex previousActivity = entity;
		int position = 0;
		
		// advance along the stream path, comparing each activity to the new one
		while (currentActivity != null && activityDateComparator
				.compare(addedActivity, currentActivity) > 0)
		{
			previousActivity = currentActivity;
			currentStreamEdge = getStreamEdge(currentActivity);
			currentActivity = getNextActivity(currentActivity);
			position++;
		}
		
		String tenantId = (String)entity.getProperty(STRING_TENANT_ID);
		String entityId = (String)entity.getProperty(STRING_ENTITY_ID);
		
		// add a stream edge between the previous activity (the one that is 
		// newer than the added one, or the entity if there are none) and the
		// new one.
		Edge newEdge = previousActivity.addEdge(STRING_STREAM, addedActivity);
		newEdge.setProperty(STRING_TENANT_ID, tenantId);
		newEdge.setProperty(STRING_ENTITY_ID, entityId);
		newEdge.setProperty(STRING_CREATED,
				DateTime.now(DateTimeZone.UTC).toString());
		
		// if there are one or more activities that are older than the added
		// one, add an edge between the added one and the next older one, and
		// delete the edge between that one and the previous (next newer) one.
		if (null != currentStreamEdge)
		{
			newEdge = addedActivity.addEdge(STRING_STREAM, currentActivity);
			newEdge.setProperty(STRING_TENANT_ID, tenantId);
			newEdge.setProperty(STRING_ENTITY_ID, entityId);
			newEdge.setProperty(STRING_CREATED,
					DateTime.now(DateTimeZone.UTC).toString());
			
			currentStreamEdge.remove();
		}
		
		return position == 0;
	}
	
	/**
	 * Adds a comment vertex at the correct chronological location among the
	 * comment vertices of an activity.
	 * 
	 * @param activity The vertex representing the activity.
	 * @param addedComment The comment to add to the activity.
	 * @param userId The user to associate with the comment. May be null.
	 */
	private void insertComment(final Vertex activity,
			final Vertex addedComment, final String userId)
	{
		Edge currentCommentEdge = getCommentEdge(activity);
		Vertex currentComment = getNextComment(activity);
		Vertex previousComment = activity;
		
		// advance along the comment path, comparing each comment to the new one
		while (currentComment != null && activityDateComparator
				.compare(addedComment, currentComment) > 0)
		{
			previousComment = currentComment;
			currentCommentEdge = getCommentEdge(currentComment);
			currentComment = getNextComment(currentComment);
		}
		
		String tenantId = (String)activity.getProperty(STRING_TENANT_ID);
		String entityId = (String)activity.getProperty(STRING_ENTITY_ID);
		String activityId = (String)activity.getProperty(STRING_ACTIVITY_ID);
		
		// add a comment edge between the previous comment (the one that is
		// newer than the added one, or the activity if there are none) and the
		// new one.
		Edge newEdge = previousComment.addEdge(STRING_COMMENTS, addedComment);
		newEdge.setProperty(STRING_TENANT_ID, tenantId);
		newEdge.setProperty(STRING_ENTITY_ID, entityId);
		newEdge.setProperty(STRING_ACTIVITY_ID, activityId);
		newEdge.setProperty(STRING_CREATED,
				DateTime.now(DateTimeZone.UTC).toString());
		
		// if there are one or more comments that are older than the added one,
		// add an edge between the added one and the next older one, and delete
		// the edge between that one and the previous (next newer) one.
		if (null != currentCommentEdge)
		{
			newEdge = addedComment.addEdge(STRING_COMMENTS, currentComment);
			newEdge.setProperty(STRING_TENANT_ID, tenantId);
			newEdge.setProperty(STRING_ENTITY_ID, entityId);
			newEdge.setProperty(STRING_ACTIVITY_ID, activityId);
			newEdge.setProperty(STRING_CREATED,
					DateTime.now(DateTimeZone.UTC).toString());
			
			currentCommentEdge.remove();
		}
		
		// if provided, create an edge relating the user to the comment
		if (null != userId && !userId.equals(""))
		{
			newEdge = getOrCreateEntityVertex(tenantId, userId)
				.addEdge(STRING_COMMENTED, addedComment);
			newEdge.setProperty(STRING_TENANT_ID, tenantId);
			newEdge.setProperty(STRING_ENTITY_ID, userId);
			newEdge.setProperty(STRING_CREATED,
					DateTime.now(DateTimeZone.UTC).toString());
		}
	}
	
	/**
	 * Retrieves the edge to the next activity from the given vertex, whether
	 * the given vertex is an entity or a activity.
	 * 
	 * @param node An activity or entity for which to find the next stream edge.
	 * @return The next stream edge in the stream containing or starting at
	 * the given vertex.
	 */
	private Edge getStreamEdge(Vertex node)
	{
		return getSingleOutgoingEdge(node, STRING_STREAM);
	}
	
	/**
	 * Retrieves the edge to the next overlay from the given vertex, whether
	 * the given vertex is an entity or an overlay.
	 * 
	 * @param node An overlay or entity for which to find the next feed edge.
	 * @return The next feed edge in the feed containing or starting at the
	 * given vertex.
	 */
	private Edge getFeedEdge(Vertex node)
	{
		return getSingleOutgoingEdge(node, STRING_FEED);
	}
	
	/**
	 * Retrieves the edge to the next comment from the given vertex, whether
	 * the given vertex is an activity or a comment.
	 * 
	 * @param node A comment or activity for which to find the next comment
	 * edge.
	 * @return The next comment edge in the comments containing or starting at
	 * the given vertex.
	 */
	private Edge getCommentEdge(Vertex node)
	{
		return getSingleOutgoingEdge(node, STRING_COMMENTS);
	}
	
	/**
	 * Retrieves the entity vertex pointed to by an overlay.
	 * 
	 * @param overlay The overlay vertex for which to find the feed entity.
	 * @return The entity vertex for the given overlay vertex.
	 */
	private Vertex getFeedEntity(Vertex overlay)
	{
		if (null == overlay)
			return null;
		
		Iterator<Vertex> vertices =
				overlay.getVertices(Direction.OUT, STRING_FEED_ENTITY)
				.iterator();
		
		Vertex entity = vertices.hasNext() ? vertices.next() : null;
		
		if (null != entity)
		{
			if (vertices.hasNext())
			{
				logger.error("Multiple feed entities for overlay with id: {}",
						overlay.getId());
			}
		}
		
		return entity;
	}
	
	/**
	 * Retrieves the overlay pointing to the given entity vertex.
	 * 
	 * @param entity The entity vertex for which to find the overlay.
	 * @param userId The entityId of the user for which to find the overlay.
	 * @return The overlay vertex for the given entity vertex.
	 */
	private Vertex getOverlayForEntity(Vertex entity, String userId)
	{
		Iterator<Vertex> vertices =
				entity.getVertices(Direction.IN, STRING_FEED_ENTITY)
				.iterator();
		
		Vertex overlay;
		
		while(vertices.hasNext())
		{
			overlay = vertices.next();
			if (userId.equals(overlay.getProperty(STRING_ENTITY_ID)))
				return overlay;
		}
		
		return null;
	}
	
	/**
	 * Retrieves the single edge emanating from the given vertex where the edge
	 * has the given label.
	 * 
	 * @param node The vertex for which to find the labeled edge.
	 * @param edgeLabel The label of the edge to find.
	 * @return The edge with the given label emanating from the given vertex, or
	 * null if the node is null or has no such edge.
	 */
	private Edge getSingleOutgoingEdge(Vertex node, String edgeLabel)
	{
		if (null == node)
			return null;
		
		Iterator<Edge> edges =
				node.getEdges(Direction.OUT, edgeLabel).iterator();
		
		Edge edge = edges.hasNext() ? edges.next() : null;
		
		if (null != edge)
		{
			if (edges.hasNext())
			{
				logger.error("Multiple outgoing edges with label: \"{}\" " +
					" for vertex: {}", edgeLabel, node.getId());
			}
		}
		
		return edge;
	}
	
	/**
	 * Adjusts the feed count for each following user. Also, if updateOrder is
	 * true, puts an entity into the correct chronological order in the feed
	 * paths of all the users that follow it. The is used when the first
	 * activity of an entity changes, which potentially changes its feed order. 
	 * 
	 * @param tenantId The ID of the tenant.
	 * @param entityId The raw ID of the entity.
	 * @param entity The entity for which followers are updated.
	 * @param activityChange The number of activities that were added or removed
	 * from the stream of the entity.
	 * @param 
	 */
	private void updateFeed(String tenantId, String entityId, Vertex entity,
			int activityChange, boolean updateOrder)
	{
		// get all the users that follow the entity
		Iterable<Vertex> usersInGraph =
				entity.getVertices(Direction.IN, STRING_FOLLOWS);
		
		// copy the users to a separate list to prevent the collection
		// underlying the iterable getting modified during processing,
		// and adjust the feed counts while copying
		ArrayList<Vertex> users = new ArrayList<Vertex>();
		for (Vertex user : usersInGraph)
		{
			users.add(user);
			adjustNumericProperty(user, STRING_FEED_COUNT, activityChange);
		}
		
		if (updateOrder)
		{
			// loop over each user and move the entity to the correct
			// feed position by un-following and re-following
			// TODO: is this the best way to do this?
			String userId;
			for (Vertex user : users)
			{
				userId = user.getProperty(STRING_ENTITY_ID);
				DateTime followed = unfollowEntity(tenantId, userId, entityId);
				followEntity(tenantId, userId, entityId, followed);
			}
		}
	}
	
	/**
	 * Increments or decrements the given numeric property of the given vertex
	 * by the given amount.
	 * 
	 * @param vertex
	 * @param property
	 * @param amount
	 */
	private void adjustNumericProperty(Vertex vertex, String property,
			int amount)
	{
		int value = (int)vertex.getProperty(property);
		value = value + amount;
		vertex.setProperty(property, value);
	}
	
	@Override
	public void deleteActivity(String tenantId, String entityId,
			String activityId)
	{
		if (null == tenantId)
			throw new IllegalArgumentException("tenantId must not be null");
		
		if (null == entityId)
			throw new IllegalArgumentException("entityId must not be null");
		
		if (null == activityId)
			throw new IllegalArgumentException("activityId must not be null");
		
		Vertex activityVertex = getActivityVertex(tenantId, entityId,
				activityId);
		
		if (null != activityVertex)
		{
			Vertex entityVertex = getOrCreateEntityVertex(tenantId, entityId);
			
			boolean firstInStream = false;
			if (activityId.equals((String)getNextActivity(entityVertex)
				.getProperty(STRING_ACTIVITY_ID)))
			{
				// if the deleted activity was first in its stream, it may have
				// changed the entity order for feed paths
				firstInStream = true;
			}
			
			removeComments(activityVertex);
			
			removeActivity(activityVertex);
			
			adjustNumericProperty(entityVertex, STRING_STREAM_COUNT, -1);

			updateFeed(tenantId, entityId, entityVertex, -1, firstInStream);
		}
		
		graph.commit();
	}
	
	/**
	 * Removes all of the comments from the given activity.
	 * 
	 * @param activityVertex The activity for which to remove comments.
	 */
	private void removeComments(Vertex activityVertex)
	{
		for (Vertex comment : getCommentVertices(
				activityVertex, 0, Integer.MAX_VALUE))
		{
			comment.remove();
		}
	}
	
	/**
	 * Deletes the given activity vertex from its stream.  The continuity of the
	 * stream is maintained.
	 * 
	 * @param activityVertex The vertex representing the activity.
	 */
	private void removeActivity(Vertex activityVertex)
	{
		Vertex followingActivity = getNextActivity(activityVertex);
		Vertex previousActivity = getPreviousActivity(activityVertex);
		activityVertex.remove();
		
		if (null != followingActivity)
		{
			Edge newEdge = previousActivity.addEdge(
					STRING_STREAM, followingActivity);
			newEdge.setProperty(STRING_TENANT_ID, previousActivity
					.getProperty(STRING_TENANT_ID));
			newEdge.setProperty(STRING_ENTITY_ID, previousActivity
					.getProperty(STRING_ENTITY_ID));
			newEdge.setProperty(STRING_CREATED,
					DateTime.now(DateTimeZone.UTC).toString());
		}
		
		activityVertex = null;
	}

	/**
	 * Deletes the given comment vertex. The continuity of the comments is
	 * maintained.
	 * 
	 * @param commentVertex The vertex representing the comment.
	 */
	private void removeComment(Vertex commentVertex)
	{
		Vertex followingComment = getNextComment(commentVertex);
		Vertex previousComment = getPreviousComment(commentVertex);
		commentVertex.remove();
		
		if (null != followingComment)
		{
			Edge newEdge = previousComment.addEdge(
					STRING_COMMENTS, followingComment);
			newEdge.setProperty(STRING_TENANT_ID, previousComment
					.getProperty(STRING_TENANT_ID));
			newEdge.setProperty(STRING_ENTITY_ID, previousComment
					.getProperty(STRING_ENTITY_ID));
			newEdge.setProperty(STRING_ACTIVITY_ID, previousComment
					.getProperty(STRING_ACTIVITY_ID));
			newEdge.setProperty(STRING_CREATED,
					DateTime.now(DateTimeZone.UTC).toString());
		}
		
		commentVertex = null;
	}

	@Override
	public ActivityStreamsCollection getStream(String tenantId, String entityId,
			int startIndex, int activitiesToReturn)
	{
		// since we need to advance from the beginning of the stream,
		// this lets us keep track of where we are
		int streamPosition = 0;
		// once we reach the number of activities to return, we can stop
		int foundActivityCount = 0;
		
		List<Vertex> activityVertices = new ArrayList<Vertex>();
		
		Vertex entityVertex = getOrCreateEntityVertex(tenantId, entityId);
		
		Vertex currentActivity = getNextActivity(entityVertex);
		
		// advance along the stream, collecting vertices after we get to the
		// start index, and stopping when we have enough to return or run out
		// of stream
		while (null != currentActivity &&
				foundActivityCount < activitiesToReturn)
		{
			if (streamPosition >= startIndex)
			{
				activityVertices.add(currentActivity);
				foundActivityCount++;
			}
			currentActivity = getNextActivity(currentActivity);
			streamPosition++;
		}
		
		graph.commit();
		
		// we only have the vertices, the actual activities need to be created
		return createCollection(activityVertices,
				(int)entityVertex.getProperty(STRING_STREAM_COUNT));
	}
	
	/**
	 * Retrieves the activity after the given node by following the outgoing
	 * stream edge. The node can be an entity (including users) or an activity.
	 * 
	 * @param node The entity or activity for which to find the next activity.
	 * @return The next activity after the given node, or null if one does not
	 * exist.
	 */
	private Vertex getNextActivity(Vertex node)
	{
		Edge streamEdge = getStreamEdge(node);
		return null == streamEdge ? null : streamEdge.getVertex(Direction.IN);
	}

	private Vertex getPreviousActivity(Vertex node)
	{
		if (null == node)
			return null;
		
		Iterator<Vertex> vertices =
				node.getVertices(Direction.IN, STRING_STREAM).iterator();
		
		Vertex activity = vertices.hasNext() ? vertices.next() : null;
		
		if (null != activity)
		{
			if (vertices.hasNext())
			{
				logger.error(
						"Multiple previous activities for node with id: {}",
						node.getId());
			}
		}
		
		return activity;
	}
	
	/**
	 * Turns a collection of activity streams object containing vertices into an
	 * ActivityStreamsCollection.
	 * 
	 * @param activityVertices The vertices to deserialize.
	 * @param totalItems The total logical number of items for the collection,
	 * which may be more than the number of vertices passed.
	 * @return An ActivityStreamsCollection that contains the objects in the
	 * given vertices.
	 */
	private ActivityStreamsCollection createCollection(
			Collection<Vertex> vertices, int totalItems)
	{
		StringBuilder sb = new StringBuilder("{\"totalItems\":");
		sb.append(totalItems);
		sb.append(",\"items\":[");
		sb.append(Joiner.on(",").join(getActivityStreamsContent(vertices)));
		sb.append("]}");
		
		return new ActivityStreamsCollection(sb.toString());
	}
	
	/**
	 * Extracts the Activity Streams formatted content from a set of vertices.
	 * 
	 * @param vertices The vertices from which to extract the content.
	 * @return A collection of the string contents of the activities.
	 */
	private List<String> getActivityStreamsContent(Collection<Vertex> vertices)
	{
		List<String> content = new ArrayList<String>();
		
		for (Vertex vertex : vertices)
		{
			content.add((String)vertex.getProperty(STRING_CONTENT));
		}
		
		return content;
	}
	
	@Override
	public DateTime followEntity(String tenantId, String userId,
			String entityId, DateTime followed)
	{
		if (null == tenantId)
		{
			throw new IllegalArgumentException("tenantId must not be null");
		}
		
		if (null == userId)
		{
			throw new IllegalArgumentException("userId must not be null");
		}
		
		if (null == entityId)
		{
			throw new IllegalArgumentException("entityId must not be null");
		}
		
		if (null == followed)
		{
			followed = DateTime.now(DateTimeZone.UTC);
		}
		
		Vertex user = getOrCreateEntityVertex(tenantId, userId);
		Vertex entity = getOrCreateEntityVertex(tenantId, entityId);
		
		for (Edge edge : user.getEdges(Direction.OUT, STRING_FOLLOWS))
		{
			if (edge.getVertex(Direction.IN).getId().equals(entity.getId()))
			{
				DateTime existingDateTime =
						DateTime.parse((String)edge
								.getProperty(STRING_CREATED));
				
				graph.commit();
				return existingDateTime;
			}
		}
		
		logger.debug("No follow relationship found for userID: {} " +
				"to entityID: {}. Creating.", userId, entityId);
		Edge followEdge = user.addEdge(STRING_FOLLOWS, entity);
		followEdge.setProperty(STRING_TENANT_ID, tenantId);
		followEdge.setProperty(STRING_CREATED, followed.toString());
		
		insertFeedEntity(user, entity, tenantId);
		
		adjustNumericProperty(user, STRING_FEED_COUNT,
				(int)entity.getProperty(STRING_STREAM_COUNT));
		adjustNumericProperty(user, STRING_FOLLOWING_COUNT, 1);
		adjustNumericProperty(entity, STRING_FOLLOWER_COUNT, 1);
		
		graph.commit();
		
		return followed;
	}
	
	@Override
	public DateTime unfollowEntity(String tenantId, String userId,
			String entityId)
	{
		if (null == tenantId)
		{
			throw new IllegalArgumentException("tenantId must not be null");
		}

		if (null == userId)
		{
			throw new IllegalArgumentException("userId must not be null");
		}

		if (null == entityId)
		{
			throw new IllegalArgumentException("entityId must not be null");
		}
		
		Vertex user = getOrCreateEntityVertex(tenantId, userId);
		Vertex entity = getOrCreateEntityVertex(tenantId, entityId);
		DateTime followed = null;
		
		// remove the follow relationship
		for (Edge edge: entity.getEdges(Direction.IN, STRING_FOLLOWS))
		{
			if (edge.getVertex(Direction.OUT).getId().equals(user.getId()))
			{
				followed = 
					DateTime.parse((String)edge.getProperty(STRING_CREATED));
				edge.remove();
			}
		}
		
		if (null != followed)
		{
			// remove the entity from the user feed by removing the overlay
			Vertex currentOverlay = getOverlayForEntity(entity, userId);
			Vertex previousOverlay = getPreviousOverlay(currentOverlay);
			Vertex nextOverlay = getNextOverlay(currentOverlay);
			currentOverlay.remove();
			
			// replace the missing edge for the feed if necessary
			if (null != nextOverlay)
			{
				Edge newEdge = previousOverlay.addEdge(STRING_FEED, nextOverlay);
				newEdge.setProperty(STRING_TENANT_ID, tenantId);
				newEdge.setProperty(STRING_ENTITY_ID, entityId);
				newEdge.setProperty(STRING_CREATED,
						DateTime.now(DateTimeZone.UTC).toString());
			}
			
			adjustNumericProperty(user, STRING_FEED_COUNT,
					-(int)entity.getProperty(STRING_STREAM_COUNT));
			adjustNumericProperty(user, STRING_FOLLOWING_COUNT, -1);
			adjustNumericProperty(entity, STRING_FOLLOWER_COUNT, -1);			
		}
		
		graph.commit();
		return followed;
	}
	
	@Override
	public DateTime getDateTimeUserFollowedEntity(String tenantId,
			String userId, String entityId)
	{
		Vertex user = getOrCreateEntityVertex(tenantId, userId);
		Vertex entity = getOrCreateEntityVertex(tenantId, entityId);
		
		for (Edge edge : user.getEdges(Direction.OUT, STRING_FOLLOWS))
		{
			if (edge.getVertex(Direction.IN).getId().equals(entity.getId()))
			{
				DateTime followDate =
					DateTime.parse((String)edge.getProperty(STRING_CREATED));
				
				graph.commit();
				
				return followDate;
			}
		}
		
		graph.commit();
		
		return null;
	}

	@Override
	public ActivityStreamsCollection getFollowing(String tenantId,
			String userId, int startIndex, int entitiesToReturn)
	{
		Vertex user = getOrCreateEntityVertex(tenantId, userId);
		ActivityStreamsCollection following = new ActivityStreamsCollection();
		
		int currentPosition = 0;
		
		// find all the followed entities and add them to the following list
		for (Edge edge : user.getEdges(Direction.OUT, STRING_FOLLOWS))
		{
			if (currentPosition >= startIndex)
			{
				ActivityStreamsObject entity = new ActivityStreamsObject();
				// remove the tenant id from the entity id before adding
				entity.setId((String)edge.getVertex(Direction.IN)
						.getProperty(STRING_ENTITY_ID));
				following.add(entity);
				if (following.size() >= entitiesToReturn)
					break;
			}
			currentPosition++;
		}
		
		following.setTotalItems((int)user.getProperty(STRING_FOLLOWING_COUNT));

		graph.commit();
		
		return following;
	}
	
	@Override
	public ActivityStreamsCollection getFollowers(String tenantId,
			String entityId, int startIndex, int followersToReturn)
	{
		Vertex entity = getOrCreateEntityVertex(tenantId, entityId);
		ActivityStreamsCollection followers = new ActivityStreamsCollection();
		
		int currentPosition = 0;
		
		// find all the following users and add them to the followers list
		for (Edge edge : entity.getEdges(Direction.IN, STRING_FOLLOWS))
		{
			if (currentPosition >= startIndex)
			{
				ActivityStreamsObject user = new ActivityStreamsObject();
				// remove the tenant id from the user id before adding
				user.setId((String)edge.getVertex(Direction.OUT)
						.getProperty(STRING_ENTITY_ID));
				followers.add(user);
				if (followers.size() >= followersToReturn)
					break;
			}
			currentPosition++;
		}
		
		followers.setTotalItems((int)entity.getProperty(STRING_FOLLOWER_COUNT));

		graph.commit();
		
		return followers;
	}
	
	@Override
	public void likeActivity(String tenantId, String userId, String entityId,
			String activityId)
	{
		if (null == tenantId)
		{
			throw new IllegalArgumentException("tenantId must not be null");
		}
		
		if (null == userId)
		{
			throw new IllegalArgumentException("userId must not be null");
		}
		
		if (null == entityId)
		{
			throw new IllegalArgumentException("entityId must not be null");
		}
		
		if (null == activityId)
		{
			throw new IllegalArgumentException("activityId must not be null");
		}
		
		Vertex activityVertex =
				getActivityVertex(tenantId, entityId, activityId);
		
		if (null == activityVertex)
			return;
		
		// already done if there is an incoming like edge matching the user ID
		if (activityVertex.query().direction(Direction.IN).labels(STRING_LIKES)
				.has(STRING_ENTITY_ID, userId).count() > 0)
			return;
		
		Vertex userVertex =
				getOrCreateEntityVertex(tenantId, userId);
						
		Edge likeEdge = userVertex.addEdge(STRING_LIKES, activityVertex);
		likeEdge.setProperty(STRING_TENANT_ID, tenantId);
		likeEdge.setProperty(STRING_ENTITY_ID, userId);
		likeEdge.setProperty(STRING_CREATED,
				DateTime.now(DateTimeZone.UTC).toString());
		
		adjustNumericProperty(activityVertex, STRING_LIKE_COUNT, 1);
		
		graph.commit();
	}

	@Override
	public void unlikeActivity(String tenantId, String userId, String entityId,
			String activityId)
	{
		if (null == tenantId)
		{
			throw new IllegalArgumentException("tenantId must not be null");
		}
		
		if (null == userId)
		{
			throw new IllegalArgumentException("userId must not be null");
		}
		
		if (null == entityId)
		{
			throw new IllegalArgumentException("entityId must not be null");
		}
		
		if (null == activityId)
		{
			throw new IllegalArgumentException("activityId must not be null");
		}
		
		Vertex activityVertex =
				getActivityVertex(tenantId, entityId, activityId);
		
		if (null == activityVertex)
			return;
		
		Edge toRemove = null;
		
		for (Edge likeEdge : activityVertex.getEdges(
				Direction.IN, STRING_LIKES))
		{
			if (userId.equals(likeEdge.getProperty(STRING_ENTITY_ID)))
			{
				toRemove = likeEdge;
				break;
			}
		}
		
		if (null != toRemove)
		{
			toRemove.remove();
			toRemove = null;
			adjustNumericProperty(activityVertex, STRING_LIKE_COUNT, -1);
		}
		
		graph.commit();
	}
	
	@Override
	public DateTime userLikesActivity(String tenantId, String userId,
			String entityId, String activityId)
	{
		Vertex activityVertex =
				getActivityVertex(tenantId, entityId, activityId);
		
		if (null == activityVertex)
			return null;
		
		for (Edge likeEdge : activityVertex.getEdges(
				Direction.IN, STRING_LIKES))
		{
			if (userId.equals(likeEdge.getProperty(STRING_ENTITY_ID)))
			{
				graph.commit();
				return DateTime.parse(
						(String)likeEdge.getProperty(STRING_CREATED));
			}
		}
		
		return null;
	}
	
	@Override
	public ActivityStreamsCollection getLikes(String tenantId,
			String entityId, String activityId, int startIndex,
			int likesToReturn)
	{
		Vertex activityVertex =
				getActivityVertex(tenantId, entityId, activityId);
		
		if (null == activityVertex)
			return null;
		
		ActivityStreamsCollection likes = new ActivityStreamsCollection();
		int currentPosition = 0;
		
		for(Edge likeEdge : activityVertex.getEdges(
				Direction.IN, STRING_LIKES))
		{
			if (likes.size() >= likesToReturn)
				break;
			
			if (currentPosition >= startIndex)
			{
				ActivityStreamsObject actor = new ActivityStreamsObject();
				actor.setId((String)likeEdge.getProperty(STRING_ENTITY_ID));
				ActivityStreamsObject activity = new ActivityStreamsObject();
				activity.setId(activityId);
				Activity like = new Activity();
				like.setActor(actor);
				like.setObject(activity);
				like.setVerb(STRING_LIKE);
				like.setPublished(DateTime.parse(
						(String)likeEdge.getProperty(STRING_CREATED)));
				
				likes.add(like);
			}
			
			currentPosition++;
		}
		
		likes.setTotalItems((int)activityVertex.getProperty(STRING_LIKE_COUNT));
		
		return likes;
	}

	/**
	 * Inserts an entity into the feed for a user.
	 * 
	 * @param user The user whose feed will be updated.
	 * @param newEntity the entity to add to the user's feed.
	 * @param tenantId the tenant for the feed.
	 * @return true if the entity is first in the feed, otherwise false.
	 */
	private boolean insertFeedEntity(final Vertex user, final Vertex newEntity,
			final String tenantId)
	{
		if (null == user)
		{
			throw new IllegalArgumentException("user must not be null");
		}
		
		if (null == newEntity)
		{
			throw new IllegalArgumentException("newEntity must not be null");
		}

		String entityId = user.getProperty(STRING_ENTITY_ID);
		String now = DateTime.now(DateTimeZone.UTC).toString();
		
		// create the overlay and attach it to the new entity
		Vertex newOverlay = graph.addVertex(null);
		newOverlay.setProperty(STRING_TYPE, STRING_OVERLAY);
		newOverlay.setProperty(STRING_TENANT_ID, tenantId);
		newOverlay.setProperty(STRING_ENTITY_ID, entityId);
		newOverlay.setProperty(STRING_CREATED, now);
		
		Edge newEdge = newOverlay.addEdge(STRING_FEED_ENTITY, newEntity);
		newEdge.setProperty(STRING_TENANT_ID, tenantId);
		newEdge.setProperty(STRING_ENTITY_ID, entityId);
		newEdge.setProperty(STRING_CREATED, now);
		
		// start with the user and the first feed overlay
		Edge currentFeedEdge = getFeedEdge(user);
		Vertex currentOverlay = getNextOverlay(user);
		Vertex previousOverlay = user;
		int position = 0;		
		
		// we order overlays based on the date of their entity's first activity
		// advance along the feed until we find where the new entity belongs
		while (currentOverlay != null && firstActivityDateComparator
				.compare(newEntity, getFeedEntity(currentOverlay)) > 0)
		{
			previousOverlay = currentOverlay;
			currentFeedEdge = getFeedEdge(currentOverlay);
			currentOverlay = getNextOverlay(currentOverlay);
			position++;
		}
		
		// add an edge from the previous overlay to the new one
		newEdge = previousOverlay.addEdge(STRING_FEED, newOverlay);
		newEdge.setProperty(STRING_TENANT_ID, tenantId);
		newEdge.setProperty(STRING_ENTITY_ID, entityId);
		newEdge.setProperty(STRING_CREATED, now);
		
		// if there are following overlays, add an edge from the new one to the
		// following one
		if (null != currentFeedEdge)
		{
			newEdge = newOverlay.addEdge(STRING_FEED, currentOverlay);
			newEdge.setProperty(STRING_TENANT_ID, tenantId);
			newEdge.setProperty(STRING_ENTITY_ID, entityId);
			newEdge.setProperty(STRING_CREATED, now);
			currentFeedEdge.remove();
		}
		
		// if we didn't advance, the entity is the first in the feed
		return position == 0;
	}

	@Override
	public ActivityStreamsCollection getFeed(String tenantId, String userId,
			int startIndex, int activitiesToReturn)
	{
		// this method represents the core of the Graphity algorithm
		// http://www.rene-pickhardt.de/graphity-an-efficient-graph-model-for-retrieving-the-top-k-news-feeds-for-users-in-social-networks/
		
		// a priority queue is used to order the activities that are "next" for
		// each entity we've already reached in the feed
		PriorityQueue<Vertex> queue =
				new PriorityQueue<Vertex>(11, activityDateComparator);
		ArrayList<Vertex> activities = new ArrayList<Vertex>();
		
		// since we need to advance from the beginning of the feed, this lets
		// us keep track of where we are
		int feedPosition = 0;
		
		// this is used to track the newest activity in the last entity reached
		Vertex topOfEntity = null;
		
		// this is used to track the newest activity within all of the streams
		// for entities that have already been reached
		Vertex topOfQueue = null;
		
		Vertex user = getOrCreateEntityVertex(tenantId, userId);
		Vertex overlay = getNextOverlay(user);
		
		if (null != overlay)
		{
			topOfEntity = getNextActivity(getFeedEntity(overlay));
			if (null != topOfEntity)
			{
				queue.add(topOfEntity);
				topOfQueue = topOfEntity;
			}
			overlay = getNextOverlay(overlay);
			topOfEntity = getNextActivity(getFeedEntity(overlay));
		}
		
		// while we have not yet hit our activities to return,
		// and there are still activities in the queue OR
		// there are more overlays
		while (activities.size() < activitiesToReturn
				&& (queue.size() > 0 || overlay != null))
		{
			// compare top of next entity to top of queue
			int result = activityDateComparator.compare(
					topOfEntity, topOfQueue);

			// if top of next entity is newer, take the top element,
			// push the next element to the queue, and move to
			// the next entity
			if (result < 0)
			{
				if (feedPosition >= startIndex)
					activities.add(topOfEntity);
				Vertex nextActivity = getNextActivity(topOfEntity);
				if (null != nextActivity)
					queue.add(nextActivity);
				overlay = getNextOverlay(overlay);
				topOfEntity = getNextActivity(getFeedEntity(overlay));
				topOfQueue = queue.peek();
			}
			
			else
			{
				// if there's no top of entity and the queue is empty,
				// we need to move to the next entity
				if (queue.isEmpty())
				{
					overlay = getNextOverlay(overlay);
					topOfEntity = getNextActivity(getFeedEntity(overlay));
				}
				// if top of queue is newer, take the top element, and
				// push the next element to the queue
				else
				{
					Vertex removedFromQueue = queue.remove();
					Vertex nextActivity = getNextActivity(removedFromQueue);
					if (null != nextActivity)
						queue.add(nextActivity);
					if (feedPosition >= startIndex)
						activities.add(removedFromQueue);
					topOfQueue = queue.peek();
				}
			}
			
			feedPosition++;
		}
		
		graph.commit();
		
		return createCollection(activities,
				(int)user.getProperty(STRING_FEED_COUNT));
	}

	/**
	 * Retrieves the overlay after the given node by following the outgoing
	 * feed edge. The node can be an entity (user) or an overlay.
	 * 
	 * @param node The entity or overlay for which to find the next overlay.
	 * @return The next overlay after the given node, or null if one does not
	 * exist.
	 */
	private Vertex getNextOverlay(Vertex node)
	{
		Edge feedEdge = getFeedEdge(node);
		return null == feedEdge ? null : feedEdge.getVertex(Direction.IN);
	}
	
	private Vertex getPreviousOverlay(Vertex node)
	{
		if (null == node)
			return null;
		
		Iterator<Vertex> vertices =
				node.getVertices(Direction.IN, STRING_FEED).iterator();
		
		Vertex overlay = vertices.hasNext() ? vertices.next() : null;
		
		if (null != overlay)
		{
			if (vertices.hasNext())
			{
				logger.error("Multiple previous overlays for node with id: {}",
						node.getId());
			}
		}
		
		return overlay;
	}
	
	@Override
	public void addComment(String tenantId, String entityId, String activityId,
			String userId, ActivityStreamsObject comment)
	{
		if (null == tenantId)
		{
			throw new IllegalArgumentException("tenantId must not be null");
		}
		
		if (null == entityId)
		{
			throw new IllegalArgumentException("entityId must not be null");
		}
		
		if (null == activityId)
		{
			throw new IllegalArgumentException("activityId must not be null");
		}
		
		if (null == comment)
		{
			throw new IllegalArgumentException("comment must not be null");
		}
		
		Vertex activityVertex =
				getActivityVertex(tenantId, entityId, activityId);
		
		if (null == activityVertex)
		{
			graph.commit();
			return;
		}
		
		Vertex commentVertex = 
				serializeComment(comment, tenantId, entityId, activityId);
		
		insertComment(activityVertex, commentVertex, userId);
		
		adjustNumericProperty(activityVertex, STRING_COMMENT_COUNT, 1);
		
		graph.commit();
	}
	
	/**
	 * Retrieves the comment after the given node by following the outgoing
	 * comments edge. The node can be an activity or a comment.
	 * 
	 * @param node The activity or comment for which to find the next comment.
	 * @return The next comment after the given node, or null if one does not
	 * exist.
	 */
	private Vertex getNextComment(Vertex node)
	{
		Edge commentEdge = getCommentEdge(node);
		return null == commentEdge ? null : commentEdge.getVertex(Direction.IN);
	}
	
	private Vertex getPreviousComment(Vertex node)
	{
		if (null == node)
			return null;
		
		Iterator<Vertex> vertices =
				node.getVertices(Direction.IN, STRING_COMMENTS).iterator();
		
		Vertex comment = vertices.hasNext() ? vertices.next() : null;
		
		if (null != comment)
		{
			if (vertices.hasNext())
			{
				logger.error(
						"Multiple previous comments for node with id: {}",
						node.getId());
			}
		}
		
		return comment;
	}
	
	@Override
	public ActivityStreamsCollection getComments(String tenantId,
			String entityId, String activityId, int startIndex,
			int commentsToReturn)
	{
		ActivityStreamsCollection comments = null;
		
		Vertex activityVertex = 
				getActivityVertex(tenantId, entityId, activityId);
		
		if (null != activityVertex)
		{
			comments = createCollection(getCommentVertices(
					activityVertex, startIndex, commentsToReturn),
					(int)activityVertex.getProperty(STRING_COMMENT_COUNT));
		}
		
		graph.commit();
		
		return comments;
	}
	
	/**
	 * Retrieves the collection of comment vertices for a given activity vertex.
	 * 
	 * @param activityVertex The vertex for which to retrieve comments.
	 * @param startIndex The zero-based index of the first comment to retrieve.
	 * @param commentsToReturn The maximum number of comments to retrieve.
	 * @return A collection of comment vertices.
	 */
	private Collection<Vertex> getCommentVertices(Vertex activityVertex,
			int startIndex, int commentsToReturn)
	{
		// since we need to advance from the beginning of the comments,
		// this lets us keep track of where we are
		int commentPosition = 0;
		// once we reach the number of comments to return, we can stop
		int foundCommentCount = 0;
		
		Vertex currentComment = getNextComment(activityVertex);
		Collection<Vertex> commentVertices = new ArrayList<Vertex>();
		
		// advance along the comments, collecting vertices after we get to the
		// start index, and stopping when we have enough to return or run out
		// of comments
		while (null != currentComment &&
				foundCommentCount < commentsToReturn)
		{
			if (commentPosition >= startIndex)
			{
				commentVertices.add(currentComment);
				foundCommentCount++;
			}
			currentComment = getNextComment(currentComment);
			commentPosition++;
		}
		
		return commentVertices;
	}

	/**
	 * A comparator for activity vertices that orders by the sort time.
	 * 
	 * @author mafuba
	 *
	 */
	private class ActivityDateComparator implements Comparator<Vertex>
	{
		@Override
		public int compare(Vertex v1, Vertex v2)
		{
			long t1 = Long.MIN_VALUE;
			if (null != v1)
				t1 = DateTime.parse((String)v1.getProperty(STRING_SORTTIME))
					.getMillis();
			long t2 = Long.MIN_VALUE;
			if (null != v2)
				t2 = DateTime.parse((String)v2.getProperty(STRING_SORTTIME))
					.getMillis();

			return new Long(t2).compareTo(t1);
		}
	}
	
	@Override
	public void deleteComment(String tenantId, String entityId,
			String activityId, String commentId)
	{
		if (null == tenantId)
			throw new IllegalArgumentException("tenantId must not be null");
		
		if (null == entityId)
			throw new IllegalArgumentException("entityId must not be null");
		
		if (null == activityId)
			throw new IllegalArgumentException("activityId must not be null");
		
		if (null == commentId)
			throw new IllegalArgumentException("commentId must not be null");
		
		Vertex commentVertex = getCommentVertex(tenantId, entityId,
				activityId, commentId);
		
		if (null != commentVertex)
		{
			removeComment(commentVertex);
			adjustNumericProperty(getActivityVertex(
					tenantId, entityId, activityId), STRING_COMMENT_COUNT, -1);
		}
		
		graph.commit();
	}
	
	/**
	 * A comparator for entity vertices that orders by the time of the first
	 * activity for the entity.
	 * 
	 * @author mafuba
	 *
	 */
	private class EntityFirstActivityDateComparator
		implements Comparator<Vertex>
	{
		@Override
		public int compare(Vertex v1, Vertex v2)
		{
			Vertex activity = null;
			
			long t1 = Long.MIN_VALUE;
			if (null != v1)
				activity = getNextActivity(v1);
			if (null != activity)
				t1 = DateTime.parse((String)activity
						.getProperty(STRING_SORTTIME)).getMillis();
			
			activity = null;
			long t2 = Long.MIN_VALUE;
			if (null != v2)
				activity = getNextActivity(v2);
			if (null != activity)
				t2 = DateTime.parse((String)activity
						.getProperty(STRING_SORTTIME)).getMillis();
			
			return new Long(t2).compareTo(t1);
		}
	}
	
	private static final String STRING_TENANT_ID = "TenantID";
	private static final String STRING_ENTITY_ID = "EntityID";
	private static final String STRING_ENTITY = "Entity";
	private static final String STRING_ACTIVITY_ID = "ActivityID";
	private static final String STRING_ACTIVITY = "Activity";
	private static final String STRING_COMMENT_ID = "CommentID";
	private static final String STRING_COMMENT = "Comment";
	private static final String STRING_COMMENT_COUNT = "CommentCount";
	private static final String STRING_COMMENTS = "Comments";
	private static final String STRING_COMMENTED = "Commented";
	private static final String STRING_SORTTIME = "SortTime";
	private static final String STRING_CONTENT = "Content";
	private static final String STRING_FOLLOWS = "Follows";
	private static final String STRING_FOLLOWING_COUNT = "FollowingCount";
	private static final String STRING_FOLLOWER_COUNT = "FollowerCount";
	private static final String STRING_STREAM = "Stream";
	private static final String STRING_STREAM_COUNT = "StreamCount";
	private static final String STRING_FEED = "Feed";
	private static final String STRING_FEED_ENTITY = "FeedEntity";
	private static final String STRING_FEED_COUNT = "FeedCount";
	private static final String STRING_OVERLAY = "Overlay";
	private static final String STRING_TYPE = "Type";
	private static final String STRING_CREATED = "Created";
	private static final String STRING_LIKES = "Likes";
	private static final String STRING_LIKE = "like";
	private static final String STRING_LIKE_COUNT = "LikeCount";
}
