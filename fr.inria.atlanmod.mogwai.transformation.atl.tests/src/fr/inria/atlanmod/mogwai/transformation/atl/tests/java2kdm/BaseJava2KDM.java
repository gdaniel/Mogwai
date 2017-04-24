package fr.inria.atlanmod.mogwai.transformation.atl.tests.java2kdm;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.gmt.modisco.java.JavaPackage;

import fr.inria.atlanmod.mogwai.common.logging.MogwaiLogger;
import fr.inria.atlanmod.neoemf.data.PersistenceBackendFactoryRegistry;
import fr.inria.atlanmod.neoemf.data.blueprints.BlueprintsPersistenceBackendFactory;
import fr.inria.atlanmod.neoemf.data.blueprints.neo4j.option.BlueprintsNeo4jOptionsBuilder;
import fr.inria.atlanmod.neoemf.data.blueprints.util.BlueprintsURI;
import fr.inria.atlanmod.neoemf.resource.PersistentResourceFactory;

public class BaseJava2KDM {

	public static void main(String[] args) throws IOException {
		Java2KDMRunner runner = new Java2KDMRunner();
		runner.enableATLDebug();
		PersistenceBackendFactoryRegistry.register(BlueprintsURI.SCHEME,
				BlueprintsPersistenceBackendFactory.getInstance());

		ResourceSet rs = new ResourceSetImpl();
		Resource targetMM = rs
				.getResource(
						URI.createFileURI("/home/gdaniel/Bureau/eclipse-mog/eclipse/workspace/org.eclipse.gmt.modisco.omg.kdm/model/kdm.ecore"),
						true);
		EPackage targetPackage = (EPackage) targetMM.getContents().get(0);
		EPackage.Registry.INSTANCE.put(targetPackage.getNsURI(), targetPackage);
		for (EPackage subPackage : targetPackage.getESubpackages()) {
			EPackage.Registry.INSTANCE.put(subPackage.getNsURI(), subPackage);
		}
		EPackage.Registry.INSTANCE.put(JavaPackage.eINSTANCE.getNsURI(), JavaPackage.eINSTANCE);

		ResourceSet rSet = new ResourceSetImpl();
		rSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());
		rSet.getResourceFactoryRegistry().getProtocolToFactoryMap()
				.put(BlueprintsURI.SCHEME, PersistentResourceFactory.getInstance());
//		Resource input = rSet.createResource(URI.createURI("materials/java/set1.xmi"));
//		input.load(Collections.emptyMap());
		Resource input = rSet.createResource(BlueprintsURI.createFileURI(new File("materials/java/neoemf/set1.graphdb")));
		Map<String, Object> options = BlueprintsNeo4jOptionsBuilder.newBuilder().autocommit().asMap();
		input.load(options);
		
		long begin = System.currentTimeMillis();
		Resource out = runner.transform(input);
		out.setURI(URI.createURI("materials/kdm/set1.xmi"));
		out.save(Collections.emptyMap());
		long end = System.currentTimeMillis();
		MogwaiLogger.info("Input size: {0}", size(input));
		MogwaiLogger.info("Output size: {0}", size(out));
		MogwaiLogger.info("Execution time: {0}ms", (end-begin));
	}

	private static int size(Resource r) {
		int size = 0;
		Iterable<EObject> allContents = r::getAllContents;
		for (EObject e : allContents) {
			size++;
		}
		return size;
	}

}
