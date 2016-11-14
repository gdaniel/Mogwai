package fr.inria.atlanmod.mogwai.util;

import java.util.List;

import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.ocl.OCL;
import org.eclipse.ocl.OCLInput;
import org.eclipse.ocl.ParserException;
import org.eclipse.ocl.ecore.Constraint;
import org.eclipse.ocl.ecore.EcoreEnvironmentFactory;
import org.eclipse.ocl.ecore.OCLExpression;
import org.eclipse.ocl.helper.OCLHelper;

import fr.inria.atlanmod.mogwai.core.MogwaiException;


class OCLParser {
	
	@SuppressWarnings("rawtypes")
	private OCL ocl;
	@SuppressWarnings("rawtypes")
	private OCLHelper oclHelper;
	
	public OCLParser(EPackage ePackage) {
		if(!EPackage.Registry.INSTANCE.containsKey(ePackage.getNsURI())) {
			EPackage.Registry.INSTANCE.put(ePackage.getNsURI(), ePackage);
		}
		this.ocl = OCL.newInstance(EcoreEnvironmentFactory.INSTANCE);
		this.oclHelper = ocl.createOCLHelper();
	}
	
	public Constraint parseTextualOCL(String oclQuery) {
		OCLInput document = new OCLInput(oclQuery);
		try {
			@SuppressWarnings("unchecked")
			List<Constraint> constraints = ocl.parse(document);
			assert constraints.size() > 0;
			// TODO Handle multiple constraints at once
			return constraints.get(0);
		} catch (ParserException e) {
			System.out.println("Can not parse OCL Query");
			e.printStackTrace();
		}
		return null;
	}
	
	@SuppressWarnings("unchecked")
	public OCLExpression parseInlineOCL(String oclExpression, EClassifier context) throws MogwaiException {
		oclHelper.setContext(context);
		try {
			return (OCLExpression) oclHelper.createQuery(oclExpression);
		} catch (ParserException e) {
			throw new MogwaiException(e.getMessage());
		}
	}
	
}