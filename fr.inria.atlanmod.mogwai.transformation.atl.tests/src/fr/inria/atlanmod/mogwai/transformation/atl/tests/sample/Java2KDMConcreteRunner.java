package fr.inria.atlanmod.mogwai.transformation.atl.tests.sample;

import static java.util.Objects.nonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.EPackageRegistryImpl;
import org.eclipse.emf.ecore.impl.EcoreFactoryImpl;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.gmt.modisco.java.JavaPackage;
import org.eclipse.m2m.atl.common.ATLLogger;
import org.eclipse.m2m.atl.common.ATL.ATLPackage;
import org.eclipse.m2m.atl.common.OCL.OCLPackage;
import org.eclipse.m2m.atl.core.ATLCoreException;
import org.eclipse.m2m.atl.core.IModel;
import org.eclipse.m2m.atl.core.IReferenceModel;
import org.eclipse.m2m.atl.core.ModelFactory;
import org.eclipse.m2m.atl.core.emf.EMFExtractor;
import org.eclipse.m2m.atl.core.emf.EMFInjector;
import org.eclipse.m2m.atl.core.emf.EMFModel;
import org.eclipse.m2m.atl.core.emf.EMFModelFactory;
import org.eclipse.m2m.atl.core.launch.ILauncher;
import org.eclipse.m2m.atl.emftvm.compiler.AtlResourceFactoryImpl;
import org.eclipse.m2m.atl.engine.emfvm.ASM;
import org.eclipse.m2m.atl.engine.emfvm.launch.EMFVMLauncher;

import fr.inria.atlanmod.mogwai.gremlin.GremlinPackage;

public class Java2KDMConcreteRunner {

	private ModelFactory modelFactory;
	private IReferenceModel javaMetamodel;
	private IReferenceModel kdmMetamodel;
	private ILauncher transformationLauncher;
	private EPackage.Registry registry;
	private EMFInjector injector;
	private EMFExtractor extractor;
	private ResourceSet rSet;
	private List<ASM> modules;
	
	
	public Java2KDMConcreteRunner() {
		try {
			// Default value
			ATLLogger.getLogger().setLevel(Level.ALL);
			
			transformationLauncher = new EMFVMLauncher();
			modelFactory = new EMFModelFactory();
			injector = new EMFInjector();
			extractor = new EMFExtractor();
			
			javaMetamodel = modelFactory.newReferenceModel();
			kdmMetamodel = modelFactory.newReferenceModel();
			
			registry = new EPackageRegistryImpl();
			registry.put(EcorePackage.eINSTANCE.getNsURI(), EcorePackage.eINSTANCE);
			registry.put(ATLPackage.eINSTANCE.getNsURI(), ATLPackage.eINSTANCE);
			registry.put(OCLPackage.eINSTANCE.getNsURI(), OCLPackage.eINSTANCE);
			registry.put(GremlinPackage.eINSTANCE.getNsURI(), GremlinPackage.eINSTANCE);
			// Dev
			registry.put(JavaPackage.eINSTANCE.getNsURI(), JavaPackage.eINSTANCE);
			ResourceSet rs = new ResourceSetImpl();
			Resource targetMM = rs.getResource(URI.createFileURI("/home/gdaniel/Bureau/eclipse-mog/eclipse/workspace/org.eclipse.gmt.modisco.omg.kdm/model/kdm.ecore"), true);
			EPackage targetPackage = (EPackage)targetMM.getContents().get(0);
			registry.put(targetPackage.getNsURI(), targetPackage);
			
			injector.inject(javaMetamodel, JavaPackage.eINSTANCE.getNsURI());
			injector.inject(kdmMetamodel, targetMM);
			
			rSet = new ResourceSetImpl();
			rSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());
			rSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("ecore", new EcoreFactoryImpl());
			rSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("atl", new AtlResourceFactoryImpl());
			
			transformationLauncher.initialize(new HashMap<String, Object>());
			modules = new ArrayList<>();
			
			InputStream java2kdmStream = getFileURL("concreteTypeDeclaration2kdmEntity.asm").openStream();
			
			modules.add((ASM) transformationLauncher.loadModule(java2kdmStream));
			
			java2kdmStream.close();
			
			
		} catch(ATLCoreException e) {
			e.printStackTrace();
		} catch(IOException e) {
			e.printStackTrace();
		}
	}
	
	public Resource transform(Resource inputResource) {
		return this.transform(inputResource, null);
	}
	
	/**
	 * Transform the input ATL resource into a Gremlin resource containing the script
	 * to execute to perform the transformation
	 * <p/>
	 * @param inputResource the resource containing the ATL model to transform
	 * @return a {@link Resource} containing the Gremlin script corresponding to the transformation
	 */
	public Resource transform(Resource inputResource, Resource outputResource) {
		try {
			
			IModel inputModel = modelFactory.newModel(javaMetamodel);
			injector.inject(inputModel, inputResource);
			
			IModel outModel = modelFactory.newModel(kdmMetamodel);
			if(nonNull(outputResource)) {
				injector.inject(outModel, outputResource);
			}
			
			transformationLauncher = new EMFVMLauncher();
			transformationLauncher.initialize(new HashMap<String, Object>());
			
			transformationLauncher.addInModel(inputModel, "IN", "java");
			transformationLauncher.addOutModel(outModel, "OUT", "kdm");
			
			transformationLauncher.launch(ILauncher.RUN_MODE, new NullProgressMonitor(), new HashMap<String, Object>(), modules.toArray());
			Resource out = null;
			long begin = System.currentTimeMillis();
			ByteArrayOutputStream os = new ByteArrayOutputStream();
			if(nonNull(outputResource)) {
				out = outputResource;
			} else {
				extractor.extract(outModel, os, null);
//				System.out.println(os.toString());
				out = rSet.createResource(URI.createURI("toutput"));
				
				try {
					out.load(new ByteArrayInputStream(os.toByteArray()), null);
					long end = System.currentTimeMillis();
					System.out.println("Time to extract created model: " + (end-begin) + "ms");
				} catch(IOException e) {
					e.printStackTrace();
				}
			}
			
			/*
			 * Unload all models and metamodels
			 */
			EMFModelFactory emfModelFactory = (EMFModelFactory) modelFactory;
			emfModelFactory.unload((EMFModel) outModel);
			emfModelFactory.unload((EMFModel)inputModel);
			return out;
		} catch(ATLCoreException e) {
			e.printStackTrace();
		}
		return null;
	}
	
	protected static URL getFileURL(String fileName) throws IOException {
		URL fileURL;
		if(isEclipseRunning()) {
			URL resourceURL = Java2KDMConcreteRunner.class.getResource(fileName);
			if(resourceURL != null) {
				fileURL = FileLocator.toFileURL(resourceURL);
			} else {
				fileURL = null;
			}
		} else {
			fileURL = Java2KDMConcreteRunner.class.getResource(fileName);
		}
		if(fileURL == null) {
			throw new IOException("'" + fileName + "' not found");
		} else {
			return fileURL;
		}
	}
	
	public static boolean isEclipseRunning() {
		try {
			return Platform.isRunning();
		} catch(Throwable e) {
			
		}
		return false;
	}
	
	public void enableATLDebug() {
		ATLLogger.getLogger().setLevel(Level.ALL);
	}
	
	public void disableATLDebug() {
		ATLLogger.getLogger().setLevel(Level.OFF);
	}
}
