package fr.inria.atlanmod.mogwai.datastore.blueprints;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EPackage;

import com.google.common.collect.Iterables;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.Index;
import com.tinkerpop.blueprints.KeyIndexableGraph;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.util.wrappers.id.IdGraph;
import com.tinkerpop.pipes.util.structures.Pair;

import fr.inria.atlanmod.mogwai.common.logging.MogwaiLogger;
import fr.inria.atlanmod.mogwai.datastore.ModelDatastore;
import fr.inria.atlanmod.mogwai.datastore.pipes.PipesDatastore;

/**
 * An implementation of {@link ModelDatastore} representing how NeoEMF maps EMF
 * models into Blueprints databases.
 * <p>
 * This mapping also implements {@link PipesDatastore}, making it available in
 * Gremlin traversals generated from Mogwai transformations.
 * <p>
 * This mapping is based on the one implicitly defined in
 * {@link BlueprintsPersistenceBackend} and {@link DirectWriteBlueprintsStore}.
 * It will be externalized in a future version of <a
 * href="www.neoemf.com">NeoEMF</a> to provide more flexibility on the EMF to
 * Blueprints mapping.
 * <p>
 * The current version of NeoEMF (1.0.1) doesn't contain class hierarchy within
 * the database. This lack of metaclass-level information avoid the definition
 * of {@link #allOfKind(String)} and {@link #isKindOf(Vertex, String)}. In
 * addition, the underlying database doesn't store attribute default value
 * information, and thus {@link #getAtt(Vertex, String)} returns {@code null}
 * for attributes that are set to their default value.
 * <p>
 * {@link NeoEMFGraphDatastore} handles these operations with two different
 * strategies:
 * <ul>
 * <li>Delegate to {@link #allOfType(String)} and
 * {@link #isTypeOf(Vertex, String)} an log a warning message. This is the
 * default solution when no {@link EPackage} parameter is set.
 * {@link #getAtt(Vertex, String)} returns {@code null} and client applications
 * have to retrieve the corresponding default value.</li>
 * <li>2) Use the provided {@code ePackage} argument of the constructor to
 * navigate in the metamodel and retrieve missing information.</li>
 * </ul>
 * 
 * @see ModelDatastore
 * @see PipesDatastore
 * 
 * @author Gwendal DANIEL
 *
 */
public class NeoEMFGraphDatastore implements ModelDatastore<Graph, Vertex, Edge, Object>,
		PipesDatastore<Graph, Vertex, Edge, Object> {

	/**
	 * The index key used to retrieve metaclass {@link Vertex} elements.
	 */
	private static final String KEY_NAME = "name";

	/**
	 * The name of the index entry holding metaclass {@link Vertex} elements.
	 */
	private static final String KEY_METACLASSES = "metaclasses";

	/**
	 * The label used for type conformance {@link Edge}s.
	 */
	private static final String KEY_INSTANCE_OF = "kyanosInstanceOf";

	/**
	 * The property key used to set metaclass name in metaclass {@link Vertex}
	 * elements.
	 */
	private static final String KEY_ECLASS_NAME = "name";

	/**
	 * The property key used to set the {@code EPackage nsURI} in metaclass
	 * {@link Vertex} elements.
	 */
	private static final String KEY_EPACKAGE_NSURI = "nsURI";

	/**
	 * The property key used to define the index of an {@link Edge} in an
	 * ordered collection.
	 */
	private static final String POSITION_KEY = "position";

	/**
	 * The value used as a separator between single and multi-valued attributes.
	 */
	private static final String SEPARATOR = ":";

	/**
	 * The property key used to access the size of an {@link Edge} collection.
	 */
	private static final String SIZE_LITERAL = "size";

	/**
	 * The label used to define {@code container} {@link Edge}s.
	 */
	private static final String CONTAINER_LABEL = "eContainer";

	/**
	 * The label used to define {@code contents} {@link Edge}s.
	 * <p>
	 * These {@link Edge}s are used to represent the containment relationship
	 * between a resource and its top-level elements.
	 */
	private static final String CONTENTS_LABEL = "eContents";

	/**
	 * The property key used to access the opposite containing feature of a
	 * {@code container} {@link Edge}.
	 */
	private static final String CONTAINING_FEATURE_KEY = "containingFeature";

	/**
	 * The {@link Graph} instance containing the model to manipulate.
	 */
	private IdGraph<KeyIndexableGraph> graph;

	/**
	 * The {@link Index} holding the metaclass {@link Vertex} elements.
	 */
	private Index<Vertex> metaclassIndex;

	/**
	 * The {@link EPackage} containing metamodel information that aren't stored
	 * in the graph.
	 * <p>
	 * This {@link EPackage} is used to compute {@link #allOfKind(String)},
	 * {@link #isKindOf(Vertex, String)}, and retrieve the default values of
	 * accessed attributes ({@link #getAtt(Vertex, String)}).
	 */
	private EPackage ePackage;

	/**
	 * Constructs a new {@link NeoEMFGraphDatastore} wrapping the provided
	 * {@code graph}.
	 * <p>
	 * <b>Note:</b> if there no {@link EPackage} is set the mapping of
	 * {@link #allOfKind(String)}, {@link #isKindOf(Vertex, String)}, and
	 * {@link #getAtt(Vertex, String)} will not be complete and may produce
	 * inconsistent output. Use {@link #NeoEMFGraphDatastore(Graph, EPackage)}
	 * or {@link #setDataSource(Graph, EPackage)} to set the {@link EPackage} to
	 * use to retrieve metamodel information that are not stored in the
	 * database.
	 * 
	 * @param graph
	 *            the underlying {@link Graph} used to store the NeoEMF model
	 * 
	 * @see NeoEMFGraphDatastore#NeoEMFGraphDatastore(Graph, EPackage)
	 * @see NeoEMFGraphDatastore#setDataSource(Graph, EPackage)
	 */
	public NeoEMFGraphDatastore(Graph graph) {
		this(graph, null);
	}

	/**
	 * Constructs a new {@link NeoEMFGraphDatastore} wrapping the provided
	 * {@code graph} and using {@code ePackage} to compute metamodel information
	 * that aren't stored in the underlying database.
	 * 
	 * @param graph
	 *            the underlying {@link Graph} used to store the NeoEMF model
	 * @param ePackage
	 *            the {@link EPackage} containing metamodel information that
	 *            aren't stored in the graph
	 */
	public NeoEMFGraphDatastore(Graph graph, EPackage ePackage) {
		this.setDataSource(graph, ePackage);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * This method also checks that the provided graph defines a metaclass
	 * index, and make it available for other methods.
	 * 
	 * @throws IllegalArgumentException
	 *             if the provided {@code graph} is not an instance of
	 *             {@link IdGraph}
	 */
	@Override
	public void setDataSource(final Graph graph) throws IllegalArgumentException {
		this.setDataSource(graph, null);
	}

	/**
	 * Set the {@code graph} to apply this mapping on and the {@code ePackage}
	 * used to retrieve metamodel informations.
	 * <p>
	 * <b>Note:</b> the previous {@link Graph} will not be accessible anymore.
	 * <p>
	 * This method also checks that the provided graph defines a metaclass
	 * index, and make it available for other methods.
	 * 
	 * @param graph
	 *            the {@link Graph} to apply this mapping on
	 * @param ePackage
	 *            the {@link EPackage} containing metamodel information that
	 *            aren't stored in the graph
	 */
	@SuppressWarnings("unchecked")
	public void setDataSource(final Graph graph, final EPackage ePackage) throws IllegalArgumentException {
		checkNotNull(graph, "No graph provided");
		checkArgument(graph instanceof IdGraph<?>, "NeoEMFMapping required a KeyIndexableGraph, found " + graph.getClass().getName());
		this.graph = (IdGraph<KeyIndexableGraph>) graph;
		this.metaclassIndex = this.graph.getIndex(KEY_METACLASSES, Vertex.class);
		this.ePackage = ePackage;
	}

	/**
	 * {@inheritDoc}
	 */
	public Graph getDataSource() {
		return graph;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Iterable<Vertex> allOfType(String typeName) {
		Vertex metaClassVertex = getMetaclassVertex(typeName, null);
		if (nonNull(metaClassVertex)) {
			return metaClassVertex.getVertices(Direction.IN, KEY_INSTANCE_OF);
		} else {
			return Collections.emptyList();
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * This method is not implemented in NeoEMF mapping because class hierarchy
	 * is not stored at the database level. This will be fixed in a future
	 * release of <a href="www.neoemf.com">NeoEMF</a>. The current
	 * implementation delegates to {@link #allOfType(String)} and logs a
	 * warning.
	 */
	@Override
	public Iterable<Vertex> allOfKind(String typeName) {
		Iterable<Vertex> result;
		if (isNull(ePackage)) {
			MogwaiLogger.warn("{0} doesn't support allOfKind mapping, computing allOfType instead", this.getClass()
					.getName());
			result = allOfType(typeName);
		} else {
			EClassifier classifier = ePackage.getEClassifier(typeName);
			if (classifier instanceof EClass) {
				EClass eClass = (EClass)classifier;
				Set<EClass> eClassesToFind = new HashSet<>();
				eClass.getEPackage().getEClassifiers()
					.stream()
					.filter(EClass.class::isInstance)
					.map(EClass.class::cast)
					.filter(c -> eClass.isSuperTypeOf(c) && ! c.isAbstract())
					.forEach(eClassesToFind::add);
				List<Iterable<Vertex>> allInstances = new ArrayList<>();
				for(EClass ec : eClassesToFind) {
					Vertex metaVertex = getMetaclassVertex(ec.getName(), null);
					if(nonNull(metaVertex)) {
						allInstances.add(metaVertex.getVertices(Direction.IN, KEY_INSTANCE_OF));
					}
				}
				result = Iterables.concat(allInstances);

			} else {
				MogwaiLogger.error("EPackage {0} doesn't contain an EClass for {1} (found {2})", ePackage.getName(),
						typeName, classifier);
				throw new IllegalStateException(MessageFormat.format(
						"EPackage {0} doesn't contain an EClass for {1} (found {2})", ePackage.getName(), typeName,
						classifier));
			}
		}
		return result;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * 
	 * @throws NullPointerException
	 *             if the provided {@code typePackageNsURI} is null (it is
	 *             required by NeoEMF to create a new instance of a given type)
	 */
	@Override
	public Vertex newInstance(final String typeName, final String typePackageNsURI, String resourceName)
			throws NullPointerException {
		long begin = System.currentTimeMillis();
		checkNotNull(graph, "Graph hasn't been initialized, call setGraph before starting graph manipulation");
		checkNotNull(typePackageNsURI, "NeoEMFMapping requires EPackage nsURI to create a new element");
		long beginGetR = System.currentTimeMillis();
//		Vertex resourceRoot = getOrCreateResourceRoot(resourceName);
		long endGetR = System.currentTimeMillis();
		newInstanceGetResourceRoot += (endGetR - beginGetR);
		// Vertex vertex = graph.addVertex(StringId.generate().toString());
		Vertex vertex = graph.addVertex(null);
		long endNewVertex = System.currentTimeMillis();
		newInstanceAddVertex += (endNewVertex - endGetR);
		Vertex eClassVertex = getMetaclassVertex(typeName, typePackageNsURI);
		if (isNull(eClassVertex)) {
			eClassVertex = createMetaclassVertex(typeName, typePackageNsURI);
			metaclassIndex.put(KEY_NAME, typeName, eClassVertex);
		}
		long endGetMetaclass = System.currentTimeMillis();
		newInstanceGetMetaclass += (endGetMetaclass - endNewVertex);
		/*
		 * Don't use setRef to set this edge, we don't need to add the property
		 * kyanosInstanceof:size in the database
		 */
		vertex.addEdge(KEY_INSTANCE_OF, eClassVertex);
		long begin2 = System.currentTimeMillis();
//		setRef(resourceRoot, CONTENTS_LABEL, null, vertex, false);
		long end = System.currentTimeMillis();
		newInstanceTime += (end - begin);
		newInstanceSetRef += (end - begin2);
		createdVertices.put(vertex.getId(), new Pair<Vertex, String>(vertex, resourceName));
		return vertex;
	}
	
	public Map<Object, Pair<Vertex,String>> createdVertices = new HashMap<>();

	public static long newInstanceTime = 0;
	public static long newInstanceSetRef = 0;
	public static long newInstanceGetResourceRoot = 0;
	public static long newInstanceAddVertex = 0;
	public static long newInstanceGetMetaclass = 0;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Vertex getParent(Vertex from) {
		return Iterables.getOnlyElement(from.getVertices(Direction.OUT, CONTAINER_LABEL), null);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Iterable<Vertex> getRef(Vertex from, String refName, String oppositeName, boolean isContainer) {
		Iterable<Vertex> result = null;
		if (isContainer && nonNull(oppositeName) && !oppositeName.equals("")) {
			/*
			 * NeoEMF doesn't store containment features with an opposite as
			 * edges, so we need to navigate the opposite to find the container
			 * of from. We can also navigate the eContainer edge, but this
			 * implies a property check to ensure the eContainer has the right
			 * type which is more expensive.
			 */
			result = from.getVertices(Direction.IN, oppositeName);
		} else {
			result = from.getVertices(Direction.OUT, refName);
		}
		return result;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Edge setRef(Vertex from, String refName, String oppositeName, Vertex to, boolean isContainment) {
		/*
		 * Note: this implementation only consider append behavior. If the
		 * transformation sets a reference at a specific position model
		 * consistency is not ensured.
		 */
		if (isContainment) {
			updateContainment(from, refName, to);
		} else {
			// NeoEMF stores opposites if they are not eContainers
			if (nonNull(oppositeName) && !oppositeName.equals("")) {
				/*
				 * We don't give an opposite to setRef to avoid infinite
				 * recursion. Note that the opposite reference cannot be a
				 * containment.
				 */
				setRef(to, oppositeName, null, from, false);
			}
		}
		Edge newEdge = from.addEdge(refName, to);
		Integer size = getSize(from, refName);
		int newSize = size + 1;
		newEdge.setProperty(POSITION_KEY, size);
		setSize(from, refName, newSize);
		return newEdge;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Vertex removeRef(Vertex from, String refName, Vertex to, boolean isContainment) {
		int size = getSize(from, refName);
		Iterable<Edge> refEdges = from.getEdges(Direction.OUT, refName);
		Vertex oldVertex = null;
		for (Edge refEdge : refEdges) {
			if (oldVertex != null) {
				// We have found the edge, update the position of the next
				// ones
				int position = refEdge.getProperty(POSITION_KEY);
				refEdge.setProperty(POSITION_KEY, position - 1);
			}
			if (refEdge.getVertex(Direction.IN).equals(to)) {
				oldVertex = refEdge.getVertex(Direction.IN);
				if (isContainment) {
					Edge containerEdge = Iterables.getFirst(oldVertex.getEdges(Direction.OUT, CONTAINER_LABEL), null);
					if (nonNull(containerEdge)) {
						containerEdge.remove();
					}
				}
				refEdge.remove();
			}
		}
		setSize(from, refName, size - 1);
		return oldVertex;
	}

	/**
	 * {@inheritDoc}
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Iterable<Object> getAtt(Vertex from, String attName) {
		Object property = from.getProperty(attName);
		Iterable<Object> result;
		if (property instanceof Iterable) {
			result = (Iterable<Object>) property;
		} else {
			if (isNull(property)) {
				if (isNull(ePackage)) {
					 
				} else {
					if (attName.equals("visibility")) {
						property = "none";
					}
					else if (attName.equals("inheritance")) {
						property = "none";
					}
					else if (attName.equals("proxy")) {
						property = "false";
					}
				}
			}
			/*
			 * Quick fix to allow Identity step definition _() on ArrayList to
			 * avoid Groovy based computation of collection operations.
			 * (Arrays.asList() returns Arrays.ArrayList, which is private and
			 * doesn't provide an accessible Groovy metaClass.
			 */
			result = new ArrayList<>(Arrays.asList(property));
		}
		return result;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Vertex setAtt(Vertex from, String attName, Object attValue) {
		if (isNull(attValue)) {
			if (attName.equals("isAbstract")) {
				from.setProperty(attName, false);
			}
		} else {
			from.setProperty(attName, attValue);
		}
		return from;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getType(Vertex from) {
		return getMetaclassVertexFor(from).getProperty(KEY_ECLASS_NAME);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isTypeOf(Vertex from, String type) {
		return getMetaclassVertexFor(from).getProperty(KEY_ECLASS_NAME).equals(type);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * This method is not implemented in NeoEMF mapping because class hierarchy
	 * is not stored at the database level. This will be fixed in a future
	 * release of <a href="www.neoemf.com">NeoEMF</a>.
	 * 
	 * @throws UnsupportedOperationException
	 *             when called, will be fixed in a future release of NeoEMF
	 */
	@Override
	public boolean isKindOf(Vertex from, String type) {
		boolean result;
		if (isNull(ePackage)) {
			result = isTypeOf(from, type);
		} else {
			result = isTypeOf(from, type); // should be implemented, here for testing purposes
//			MogwaiLogger.error("isKindOf support with an EPackage is not defined for now");
//			throw new IllegalStateException("isKindOf support with an EPackage is not defined for now");
		}
		return result;
	}

	/**
	 * Get the {@link Vertex} corresponding to the root of the resource
	 * {@code resourceName}.
	 * <p>
	 * This method creates a new root {@link Vertex} if the database doesn't
	 * contain the requested one.
	 * 
	 * @param resourceName
	 *            the name of the resource to get the root of
	 * @return the {@link Vertex} corresponding to the root of the resource, or
	 *         a new {@link Vertex} if it doesn't exist
	 */
	private Vertex getOrCreateResourceRoot(String resourceName) {
		Vertex eObjectMetaclass = getMetaclassVertex("EObject", null);
		Iterable<Vertex> resourceRoots = eObjectMetaclass.getVertices(Direction.IN, KEY_INSTANCE_OF);
		for (Vertex rRoot : resourceRoots) {
			if (rRoot.getId().equals(resourceName)) {
				return rRoot;
			}
		}
		Vertex newResourceRoot = graph.addVertex(resourceName);
		newResourceRoot.addEdge(KEY_INSTANCE_OF, eObjectMetaclass);
		return newResourceRoot;
	}

	/**
	 * Returns the {@link Vertex} containing the metaclass information for
	 * {@code typeName} and {@code typePackageNsURI}.
	 * <p>
	 * If the provided {@code typePackageNsURI} is {@code null} the first
	 * metaclass matching the provided {@code typeName} is returned.
	 * 
	 * @param typeName
	 *            the name of the metaclass to search
	 * @param typePackageNsURI
	 *            the {@code nsURI} of the {@code EPackage} containing the
	 *            metaclass to search
	 * @return a {@link Vertex} corresponding to the metaclass, or {@code null}
	 *         if it doesn't exist in the graph
	 */
	private Vertex getMetaclassVertex(final String typeName, final String typePackageNsURI) {
		checkNotNull(metaclassIndex,
				"Metaclass index cannot be found, call setGraph before starting graph manipulation");
		if (isNull(typePackageNsURI)) {
			return Iterables.getOnlyElement(metaclassIndex.get(KEY_NAME, typeName), null);
		} else {
			Iterable<Vertex> metaclasses = metaclassIndex.get(KEY_NAME, typeName);
			for (Vertex mm : metaclasses) {
				if (mm.getProperty(KEY_EPACKAGE_NSURI).equals(typePackageNsURI)) {
					return mm;
				}
			}
			return null;
		}
	}

	/**
	 * Creates a new {@link Vertex} to store the metaclass {@code typeName}
	 * information.
	 * 
	 * @param typeName
	 *            the name of the metaclass to create
	 * @param typePackageNsURI
	 *            a string representation of the URI of the EPackage containing
	 *            the metaclass to store
	 * @return the created {@link Vertex}
	 */
	private Vertex createMetaclassVertex(final String typeName, final String typePackageNsURI) {
		Vertex vertex = graph.addVertex(new StringBuilder(typeName).append('@').append(typePackageNsURI).toString());
		vertex.setProperty(KEY_ECLASS_NAME, typeName);
		vertex.setProperty(KEY_EPACKAGE_NSURI, typePackageNsURI);
		return vertex;
	}

	/**
	 * Returns the {@link Vertex} containing the metaclass information for
	 * {@code instanceVertex}.
	 * 
	 * @param instanceVertex
	 *            the {@link Vertex} representing the instance to compute the
	 *            type from
	 * @return the {@link Vertex} containing the metclass information for
	 *         {@code instanceVertex}
	 * @throws IllegalStateException
	 *             if {@code instanceVertex} doesn't have an associated
	 *             metaclass
	 */
	private Vertex getMetaclassVertexFor(final Vertex instanceVertex) throws IllegalStateException {
		Vertex metaclassVertex = Iterables.getOnlyElement(instanceVertex.getVertices(Direction.OUT, KEY_INSTANCE_OF),
				null);
		if (nonNull(metaclassVertex)) {
			return metaclassVertex;
		} else {
			throw new IllegalStateException(MessageFormat.format("Cannot find the metaclass vertex of {0}",
					instanceVertex.getId()));
		}
	}

	/**
	 * Returns the size of the given {@code feature}.
	 * 
	 * @param vertex
	 *            the input {@link Vertex} of the {@code feature}
	 * @param feature
	 *            the name of the feature to compute the size of
	 * @return the size of the {@code feature} if it is multi-valued, {@code 0}
	 *         otherwise
	 */
	private Integer getSize(Vertex vertex, String feature) {
		Integer size = vertex.getProperty(feature + SEPARATOR + SIZE_LITERAL);
		return isNull(size) ? 0 : size;
	}

	/**
	 * Sets the size of the given {@code feature} to {@code size}.
	 * 
	 * @param vertex
	 *            the input {@link Vertex} of the {@code feature}
	 * @param feature
	 *            the name of the feature to set the size of
	 * @param size
	 *            the new size to set
	 */
	private void setSize(Vertex vertex, String feature, int size) {
		if (size == 0) {
			vertex.removeProperty(feature + SEPARATOR + SIZE_LITERAL);
		} else {
			vertex.setProperty(feature + SEPARATOR + SIZE_LITERAL, size);
		}
	}

	/**
	 * Updates the containment by creating a new container edge between
	 * {@code to} and {@code from}.
	 * <p>
	 * <b>Note:</b> this method removes all outgoing container edges from
	 * {@code to} before creating the new container edge.
	 * 
	 * @param from
	 *            the {@link Vertex} element representing the container
	 * @param refName
	 *            the name of the reference to set
	 * @param to
	 *            the {@link Vertex} representing the contained element
	 */
	private void updateContainment(Vertex from, String refName, Vertex to) {
		updateContainmentCount++;
		long begin = System.currentTimeMillis();
		// Find the old containment reference name and remove it
		long begin1 = System.currentTimeMillis();
		for (Edge edge : to.getEdges(Direction.OUT, CONTAINER_LABEL)) {
			removeRef(from, (String) edge.getProperty(CONTAINING_FEATURE_KEY), to, true);
			break;
		}
		long end1 = System.currentTimeMillis();

		// Remove eContents edges if the element is a top-level element
		long begin2 = System.currentTimeMillis();
		if(createdVertices.containsKey(to.getId())) {
			createdVertices.remove(to.getId());
		}
//		for (Vertex rootVertex : to.getVertices(Direction.IN, CONTENTS_LABEL)) {
//			removeRef(rootVertex, CONTENTS_LABEL, to, false);
//			break;
//		}
		long end2 = System.currentTimeMillis();
		Edge edge = to.addEdge(CONTAINER_LABEL, from);
		edge.setProperty(CONTAINING_FEATURE_KEY, refName);
		long end = System.currentTimeMillis();
		updateContainmentTime += (end - begin);
		updateContainment1 += (end1 - begin1);
		updateContainment2 += (end2 - begin2);
	}
	
	@Override
	public void close() {
		for(Pair<Vertex, String> v : createdVertices.values()) {
			Vertex resourceRoot = getOrCreateResourceRoot(v.getB());
			setRef(resourceRoot, CONTENTS_LABEL, null, v.getA(), false);
		}
		createdVertices = null;
	}
	
	public static int updateContainmentCount = 0;

	public static long updateContainmentTime = 0;
	public static long updateContainment1 = 0;
	public static long updateContainment2 = 0;
}
