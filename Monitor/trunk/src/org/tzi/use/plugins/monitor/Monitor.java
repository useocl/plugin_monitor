/*
 * USE - UML based specification environment
 * Copyright (C) 1999-2010 Mark Richters, University of Bremen
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

// $Id$

package org.tzi.use.plugins.monitor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Stack;

import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.tzi.use.main.Session;
import org.tzi.use.uml.mm.MAssociation;
import org.tzi.use.uml.mm.MAssociationClass;
import org.tzi.use.uml.mm.MAssociationEnd;
import org.tzi.use.uml.mm.MAttribute;
import org.tzi.use.uml.mm.MClass;
import org.tzi.use.uml.mm.MElementAnnotation;
import org.tzi.use.uml.mm.MNavigableElement;
import org.tzi.use.uml.mm.MOperation;
import org.tzi.use.uml.mm.MPrePostCondition;
import org.tzi.use.uml.ocl.expr.ExpObjRef;
import org.tzi.use.uml.ocl.expr.Expression;
import org.tzi.use.uml.ocl.expr.ExpressionWithValue;
import org.tzi.use.uml.ocl.value.ObjectValue;
import org.tzi.use.uml.ocl.value.StringValue;
import org.tzi.use.uml.ocl.value.UndefinedValue;
import org.tzi.use.uml.ocl.value.Value;
import org.tzi.use.uml.sys.MObject;
import org.tzi.use.uml.sys.MOperationCall;
import org.tzi.use.uml.sys.MSystem;
import org.tzi.use.uml.sys.MSystemException;
import org.tzi.use.uml.sys.ppcHandling.PPCHandler;
import org.tzi.use.uml.sys.ppcHandling.PostConditionCheckFailedException;
import org.tzi.use.uml.sys.ppcHandling.PreConditionCheckFailedException;
import org.tzi.use.uml.sys.soil.MAttributeAssignmentStatement;
import org.tzi.use.uml.sys.soil.MEnterOperationStatement;
import org.tzi.use.uml.sys.soil.MExitOperationStatement;
import org.tzi.use.uml.sys.soil.MLinkDeletionStatement;
import org.tzi.use.uml.sys.soil.MLinkInsertionStatement;
import org.tzi.use.uml.sys.soil.MNewObjectStatement;
import org.tzi.use.util.Log;
import org.tzi.use.util.StringUtil;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector.Argument;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.ModificationWatchpointEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.MethodExitRequest;
import com.sun.jdi.request.ModificationWatchpointRequest;
import com.sun.tools.jdi.SocketAttachingConnector;

/**
 * This class handles the monitoring of a Java application
 * via the remote debugger.
 * It connects to the virtual machine and keeps track of
 * operation calls and instance creation when attached.
 * 
 * @author lhamann
 */
public class Monitor implements ChangeListener {
	/**
	 * The host name where the monitored VM is running on
	 */
	private String host;
	/**
	 * The port where the monitored VM is providing debugging events
	 */
	private int port;
	/**
	 * The USE session which provides information about the relevant
	 * classes and operations to listen for.
	 */
	private Session session;
	/**
	 * The monitored virtual machine
	 */
	private VirtualMachine monitoredVM = null;
	/**
	 * True when monitoring, e.g., USE monitor is connected
	 * to a VM
	 */
    private boolean isRunning = false;
    /**
	 * True when monitoring and vm is paused
	 */
    private boolean isPaused = false;
    
    private SocketAttachingConnector connector;
    
    /**
     * This thread handles breakpoint events. E.g. call of a method.
     */
    private Thread breakpointWatcher;
    
    /**
     * Saves the mapping between USE and Java objects.
     */
    private Map<ObjectReference, MObject> instanceMapping;
    
    private Object failedOperationLock = new Object();
    
    private boolean hasFailedOperation = false;
    
    private int countInstances;
    
    private int countLinks;
    
    private int countAttributes;
    
    private List<IMonitorStateListener> stateListener = new LinkedList<IMonitorStateListener>();
    
    private List<IProgressListener> snapshotProgressListener = new LinkedList<IProgressListener>();
    
    private boolean isResetting = false;
    
    private long maxInstances = 10000;
    
    /**
     * When true, Soil statements are used for system state manipulation.
     * Otherwise the objects are created directly by using operations of
     * system and systemstate
     */
    private boolean useSoil = true;
    
    public Monitor() { }
    
    private MSystem getSystem() {
    	return session.system();
    }
    
    public void configure(Session session, String host, String port) {
    	this.session = session;
    	this.session.addChangeListener(this);
    	
    	MElementAnnotation modelAnnotation = getSystem().model().getAnnotation("Monitor");
    	
    	if ("".equals(host)) {
    		if (modelAnnotation != null && modelAnnotation.hasAnnotationValue("host")) {
    			this.host = modelAnnotation.getAnnotationValue("host");
    		} else {
    			this.host = "localhost";
    		}
    	} else {
    		this.host = host;
    	}
    	
    	if ("".equals(port)) {
    		if (modelAnnotation != null && modelAnnotation.hasAnnotationValue("port")) {
    			this.port = Integer.parseInt( modelAnnotation.getAnnotationValue("port") );
    		} else {
    			this.port = 6000;
    		}
    	} else {
    		this.port = Integer.parseInt(port);
    	}
    }
    
    public boolean isRunning() {
    	return this.isRunning;
    }
    
    public boolean isPaused() {
    	return this.isRunning && this.isPaused;
    }
    
    protected void doUSEReset() {
    	isResetting = true;
    	session.reset();
    	isResetting = false;
    }
    
    private String getJavaClassName(MClass cls) {
    	String classPackage = cls.getAnnotationValue("Monitor", "package");
    	String className = cls.getAnnotationValue("Monitor", "name");
    	
    	if (className == "")
    		className = cls.name();
    	
    	if (classPackage == "")
    		classPackage = getSystem().model().getAnnotationValue("Monitor", "defaultPackage");
    	
    	return classPackage + "." + className;
    }
    
    private String getJavaFieldName(MAttribute a) {
		String name = a.getAnnotationValue("Monitor", "name");
		if (name == "") {
			name = a.name();
		}
		return name;
	}
    
    private void waitForUserInput() {
    	synchronized (failedOperationLock) {
    		try {
    			failedOperationLock.wait();
    		} catch (InterruptedException e) {
    			e.printStackTrace();
    		}
		}
    }
    
    public void start(boolean suspend) {
    	doUSEReset();
    	
    	try {
			attachToVM();
		} catch (MonitorException e) {
			Log.error(e);
			return;
		}
		
		registerClassPrepareEvents();
		
		System.out.println("Setting operation breakpoints");
		registerOperationBreakPoints();
		
		this.instanceMapping = new HashMap<ObjectReference, MObject>();
		
		breakpointWatcher = new Thread(new Runnable() {
			@Override
			public void run() {
				while (isRunning) {
					try {
						EventSet events = monitoredVM.eventQueue().remove();
						for (com.sun.jdi.event.Event e : events) {
							if (e instanceof BreakpointEvent) {
								BreakpointEvent be = (BreakpointEvent)e;
								boolean opCallResult = onMethodCall(be);
								if (!opCallResult) {
									hasFailedOperation = true;
									waitForUserInput();
								}
							} else if (e instanceof ClassPrepareEvent) {
								ClassPrepareEvent ce = (ClassPrepareEvent)e;
								registerOperationBreakPoints(ce.referenceType());
								Log.debug("Registering operations of prepared Java class " + ce.referenceType().name() + ".");
							} else if (e instanceof ModificationWatchpointEvent) {
								ModificationWatchpointEvent we = (ModificationWatchpointEvent)e;
								updateAttribute(we.object(), we.field(), we.valueToBe());
							} else if (e instanceof MethodExitEvent) {
								boolean opCallResult = onMethodExit((MethodExitEvent)e);
								if (!opCallResult) {
									hasFailedOperation = true;
									waitForUserInput();
								}
							}
						}
						events.resume();
					} catch (InterruptedException e) {
						// VM is away np
					} catch (VMDisconnectedException e) {
						isRunning = false;
						monitoredVM = null;
						Log.warn("Monitored application has terminated");
					}
				}
			}});
		breakpointWatcher.start();
		
		monitoredVM.resume();
		isRunning = true;
		isPaused = false;
		
		onMonitorStart();
		
		if (suspend) {
			pause(false);
		}
    }

    public void pause() {
    	pause(true);
    }
    
    protected void pause(boolean doReset) {
    	monitoredVM.suspend();
		
    	if (doReset) {
    		doUSEReset();
    	}
    	
		long start = System.currentTimeMillis();
		countInstances = 0;
		countLinks = 0;
		countAttributes = 0;
		
		Log.println("Creating snapshot using" + (useSoil ? " SOIL statements." : " using system operations." ));
    	readInstances();
    	
    	long end = System.currentTimeMillis();
    	Log.println("Read " + countInstances + " instances and " + countLinks + " links in " + (end - start) + "ms.");
    	
    	isPaused = true;
    	onMonitorPause();
    }

    public void resume() {
    	synchronized (failedOperationLock) {
    		if (hasFailedOperation) {
        		failedOperationLock.notify();
        		hasFailedOperation = false;
        	}
		}
    	
    	monitoredVM.resume();
    	isPaused = false;
    	onMonitorResume();
    }
    
    public void end() {
    	if (monitoredVM != null)
    		monitoredVM.dispose();
    	
    	instanceMapping = null;
    	isRunning = false;
    	isPaused = false;
    	onMonitorEnd();
    }
    
    public boolean hasSnapshot() {
    	return instanceMapping != null;
    }
    
    private ReferenceType getReferenceClass(MClass cls) {
    	
    	List<ReferenceType> classes = monitoredVM.classesByName(getJavaClassName(cls));
    	
    	if (classes.size() == 1) {
    		return classes.get(0);
    	} else {
    		return null;
    	}
    }
    
    private void attachToVM() throws MonitorException {
		connector = new SocketAttachingConnector();
    	@SuppressWarnings("unchecked")
		Map<String, Argument> args = connector.defaultArguments();
    	
    	args.get("hostname").setValue(host);
    	args.get("port").setValue(Integer.toString(port));
    	
    	try {
			monitoredVM = connector.attach(args);
		} catch (IOException e) {
			throw new MonitorException("Could not connect to virtual machine", e);
		} catch (IllegalConnectorArgumentsException e) {
			throw new MonitorException("Could not connect to virtual machine", e);
		}
		
		System.out.println("Connected to virtual machine");
	}
    
	private void registerClassPrepareEvents() {
		for (MClass cls : getSystem().model().classes()) {
			String javaClassName = getJavaClassName(cls);
			
			ClassPrepareRequest req = monitoredVM.eventRequestManager().createClassPrepareRequest();
			req.addClassFilter(javaClassName);
			req.setSuspendPolicy(EventRequest.SUSPEND_ALL);
			req.enable();
		}
	}
	
	/**
	 * Sets a break point for each defined USE operation (if it matches a Java method) 
	 * of all currently loaded classes in the VM.
	 */
    private void registerOperationBreakPoints() {
    	for (MClass cls : getSystem().model().classes()) {
    		registerOperationBreakPoints(cls);
    	}
    }

    /**
	 * Sets a break point for each defined USE operation (if it matches a Java method) 
	 * of the specified USE class, if the corresponding Java class 
	 * is loaded classes in the VM.
	 */
	private void registerOperationBreakPoints(MClass cls) {
		ReferenceType refType = getReferenceClass(cls);
		    		
		if (refType != null) {
			Log.debug("Registering operation breakpoints for class " + cls.name());
			for (MOperation op : cls.operations()) {
				String isQuery = op.getAnnotationValue("Monitor", "isQuery");
				if (isQuery.equals("true")) continue;
				
				List<com.sun.jdi.Method> methods = refType.methodsByName(op.name());
				for (com.sun.jdi.Method m : methods) {
					// TODO: Check parameter types
					Log.debug("Registering operation breakpoint for operation " + m.name());
					BreakpointRequest req = monitoredVM.eventRequestManager().createBreakpointRequest(m.location());
					req.setSuspendPolicy(EventRequest.SUSPEND_ALL);
					req.enable();
				}
			}
			
			// Breakpoints for constructors
			for (Method m : refType.methods()) {
				if (m.isConstructor()) {
					Log.debug("Registering constructor " + m.toString());
					BreakpointRequest req = monitoredVM.eventRequestManager().createBreakpointRequest(m.location());
					req.setSuspendPolicy(EventRequest.SUSPEND_ALL);
					req.enable();
				}
			}
			
			for (MAttribute a : cls.attributes()) {
				String aName = getJavaFieldName(a);
				Field f = refType.fieldByName(aName);
				if (f == null) {
					Log.warn("Unknown attribute " + StringUtil.inQuotes(a.name()));
					continue;
				}
				
				ModificationWatchpointRequest req = monitoredVM.eventRequestManager().createModificationWatchpointRequest(f);
				req.setSuspendPolicy(EventRequest.SUSPEND_NONE);
				req.enable();
			}
			
			// Association ends with multiplicity 1 can be handled also
			for (Map.Entry<String, MNavigableElement> end : cls.navigableEnds().entrySet()) {
				if (!end.getValue().isCollection()) {
					Field f = refType.fieldByName(end.getKey());
					if (f != null) {
						ModificationWatchpointRequest req = monitoredVM.eventRequestManager().createModificationWatchpointRequest(f);
						req.setSuspendPolicy(EventRequest.SUSPEND_NONE);
						req.enable();
					}
				}
			}
		}
	}

	private void registerOperationBreakPoints(ReferenceType refType) {
		// Get the last element of the qualified name
		String name = refType.name();
		name = name.substring(name.lastIndexOf(".") + 1);
		
		MClass cls = getSystem().model().getClass(name);
		if (cls != null) {
			registerOperationBreakPoints(cls);
		}
	}
	
	private Map<MClass, Set<ReferenceType>> classMappings;
	
	/**
	 * Constructs a map which maps each use class
	 * to the reference types that must be read. 
	 */
	private void setupClassMappings() {
		Collection<MClass> useClasses = getSystem().model().classes();
		classMappings = new HashMap<MClass, Set<ReferenceType>>(useClasses.size());
		
		// Build a map of Java names to USE classes
		Map<String, MClass> classNamesMap = new HashMap<String, MClass>(useClasses.size());
		for (MClass useClass : useClasses) {
			if (!useClass.getAnnotationValue("Monitor", "ignore").equals("true")) {
				classNamesMap.put(getJavaClassName(useClass), useClass);
				classMappings.put(useClass, new HashSet<ReferenceType>());
			}
		}
		
		for (ReferenceType type : monitoredVM.allClasses()) {
			if (!(type instanceof ClassType)) continue;

			ClassType cType = (ClassType)type;
			
			if (classNamesMap.containsKey(cType.name())) {
				MClass useClass = classNamesMap.get(cType.name());
				Set<ReferenceType> javaClasses = classMappings.get(useClass);
				javaClasses.add(cType);
				
				if (useClass.getAnnotationValue("Monitor", "ignoreSubclasses").equals("true"))
					continue;

				Stack<ClassType> toDo = new Stack<ClassType>();
				toDo.push(cType);
				while (!toDo.isEmpty()) {
					ClassType toCheck = toDo.pop();
					for (ClassType subType : toCheck.subclasses()) {
						if (!classNamesMap.containsKey(subType.name())) {
							javaClasses.add(subType);
							toDo.push(subType);
						}
					}
				}
			}
		}
	}
	
	private void readInstances() {
		long start = System.currentTimeMillis();
		Collection<MClass> classes = getSystem().model().classes();
		
		onSnapshotStart("Initializing...", classes.size());
		
		instanceMapping = new HashMap<ObjectReference, MObject>();
		setupClassMappings();
		
		ProgressArgs args = new ProgressArgs("Reading instances", 0, classes.size());
		// Create current system state
    	for (MClass cls : classes) {
    		onSnapshotProgress(args);
    		if (!cls.getAnnotationValue("Monitor", "ignore").equals("true")) {
    			readInstances(cls);
    		}
    		args.setCurrent(args.getCurrent() + 1);
    	}
    	
    	long end = System.currentTimeMillis();
    	long duration = (end - start);
    	
		Log.println(" Created " + countInstances + " instances in " + duration
				+ "ms (" + (double)countInstances / ((double)duration / 1000) + " instances/s).");
    	
		onSnapshotEnd();
		
    	readAttributtesAndLinks();
    	classMappings = null;
	}
	
    private void readInstances(MClass cls) {
    	
    	// Find all subclasses of the reference type which are not modeled in
    	// the USE file, because instances() only returns concrete instances
    	// of a type (and not of subclasses) 
    	Set<ReferenceType> typesToRead = classMappings.get(cls);

    	if (typesToRead.isEmpty()) {
			Log.debug("Java class "
					+ StringUtil.inQuotes(getJavaClassName(cls))
					+ " could not be found for USE class "
					+ StringUtil.inQuotes(cls.name())
					+ ". Maybe not loaded, yet.");
			return;
    	}

		for (ReferenceType refType : typesToRead) {
	    	List<ObjectReference> refInstances = refType.instances(getMaxInstances());
			
			for (ObjectReference objRef : refInstances) {
				if (objRef.isCollected()) continue;
				
				try {
					createInstance(cls, objRef);
					++countInstances;
				} catch (MSystemException e) {
					Log.error(e);
					return;
				}
			}
		}
    }

	public long getMaxInstances() {
		return maxInstances;
	}

	public void setMaxInstances(long newValue) {
		maxInstances = newValue;
	}
	
	/**
	 * @param cls
	 * @param objRef
	 * @throws MSystemException
	 */
	protected void createInstance(MClass cls, ObjectReference objRef)
			throws MSystemException {
		if (useSoil) {
			MNewObjectStatement stmt = new MNewObjectStatement(cls);
			getSystem().evaluateStatement(stmt);
			instanceMapping.put(objRef, stmt.getCreatedObject());
		} else {
			String name = getSystem().state().uniqueObjectNameForClass(cls);
			MObject o = getSystem().state().createObject(cls, name);
			instanceMapping.put(objRef, o);
		}
	}
    
    /**
     * Reads all attributes of all read instances.
     * Must be done after instance creation to allow
     * link creation.
     */
    private void readAttributtesAndLinks() {
    	long start = System.currentTimeMillis();

    	int progressEnd = instanceMapping.size() * 2;
    	onSnapshotStart("Reading attributes and links...", progressEnd);
    	ProgressArgs args = new ProgressArgs("Reading attributes", progressEnd);
    	// Maximum number of progress calls 50
    	int step = progressEnd / 50;
    	int counter = 0;
    	
    	for (Map.Entry<ObjectReference, MObject> entry : instanceMapping.entrySet()) {
    		readAttributes(entry.getKey(), entry.getValue());
    		
    		if (counter % step == 0) {
    			args.setCurrent(counter);
    			onSnapshotProgress(args);
    		}
    		counter++;
    	}
    	    	
    	long end = System.currentTimeMillis();
    	long duration = (end - start);
		Log.println(" Setting " + countAttributes + " attributes took " + duration + "ms ("
				+ (double)countAttributes / (duration / 1000) + " attributes/s).");
		
		start = System.currentTimeMillis();
		
    	for (Map.Entry<ObjectReference, MObject> entry : instanceMapping.entrySet()) {
    		readLinks(entry.getKey(), entry.getValue());
    		
    		if (counter % step == 0) {
    			args.setCurrent(counter);
    			onSnapshotProgress(args);
    		}
    		counter++;
    	}
    	
    	onSnapshotEnd();
    	
    	end = System.currentTimeMillis();
    	duration = (end - start);
		Log.println(" Creating " + countLinks + " links took " + duration + "ms ("
				+ ((double)countLinks) / (duration / 1000) + " links/s).");
    }
    
    private void updateAttribute(ObjectReference obj, Field field, com.sun.jdi.Value javaValue) {
    	if (!hasSnapshot()) return;
    	Log.debug("updateAttribute: " + field.name());
    	
    	MObject useObject = instanceMapping.get(obj);
    	
    	if (useObject == null) {
			Log.warn("No coresponding USE-object found to set value of attribute "
					+ StringUtil.inQuotes(field.name())
					+ " to "
					+ StringUtil.inQuotes(javaValue == null ? "undefined" : javaValue
							.toString()));
			return;
    	}
    	
    	MAttribute attr = useObject.cls().attribute(field.name(), true); 
    	
    	if (attr == null) {
    		// Link end?
    		MNavigableElement end = useObject.cls().navigableEnd(field.name());
    		if (end != null && !end.isCollection()) {
    			// Destroy possible existing link
    			List<MAssociationEnd> ends = new ArrayList<MAssociationEnd>(end.association().associationEnds());
    			ends.remove(end);
    			
    			List<MObject> objects = getSystem().state().getNavigableObjects(useObject, ends.get(0), end, Collections.<Value>emptyList());
    			
    			if (objects.size() > 0) {
    				MLinkDeletionStatement delStmt = new MLinkDeletionStatement(end.association(), objects.get(0), useObject);
	    			try {
	    				getSystem().evaluateStatement(delStmt);
					} catch (MSystemException e) {
						Log.warn("Link of association " + StringUtil.inQuotes(end.association()) + " could not be deleted. Reason: " + e.getMessage());
						return;
					}
    			}
    			
    			// Create link if needed
				if (javaValue != null) {
					Value newValueV = getUSEValue(javaValue, true);
					if (newValueV.isUndefined()) return;
					
					MObject newValue = ((ObjectValue)newValueV).value();
					MObject[] linkObjects = new MObject[2];
					
					if (end.association().associationEnds().get(0).equals(end) ) {
						linkObjects[0] = newValue;
						linkObjects[1] = useObject;
					} else {
						linkObjects[0] = newValue;
						linkObjects[1] = useObject;
					}
					
					try {
						MLinkInsertionStatement createStmt = new MLinkInsertionStatement(
								end.association(), linkObjects, Collections.<List<Value>>emptyList()
						);
						getSystem().evaluateStatement(createStmt);
					} catch (MSystemException e) {
						Log.warn("Could not create new link");
					}
				}
    		}
    	} else {
    		Value v = getUSEValue(javaValue, attr.type().isObjectType());
			MAttributeAssignmentStatement stmt = new MAttributeAssignmentStatement(
					new ExpObjRef(useObject), attr, v);
    		
			try {
				getSystem().evaluateStatement(stmt);
			} catch (MSystemException e) {
				Log.warn("Attribute " + StringUtil.inQuotes(attr.toString()) + " could not be set!");
			}
    	}
    }
    
    /**
     * Checks the {@link ReferenceType} of <code>objRef</code> for the attributes
     * of the USE class of <code>o</code>. If an attribute is found the value of the 
     * USE attribute is set. After that all associations the USE class is participating
     * in are checked for rolenames matching an attribute name. For each match corresponding
     * links are created.  
     * @param objRef The Java instance to read the values from 
     * @param o The use object to set the values for.
     */
    private void readAttributes(ObjectReference objRef, MObject o) {
    	for (MAttribute attr : o.cls().allAttributes()) {
    		
    		if (!readSpecialAttributeValue(objRef, o, attr)) {
	    		Field field = objRef.referenceType().fieldByName(getJavaFieldName(attr));
	    		
	    		if (field != null) {
	    			com.sun.jdi.Value val = objRef.getValue(field);
	    			Value v = getUSEValue(val, attr.type().isObjectType()); 
	    			try {
	    				if (useSoil) {
	    					MAttributeAssignmentStatement stmt = 
	    						new MAttributeAssignmentStatement(o, attr, v);
	    					getSystem().evaluateStatement(stmt);
	    				} else {
	    					o.state(getSystem().state()).setAttributeValue(attr, v);
	    				}
	    				++countAttributes;
	    			} catch (IllegalArgumentException e) {
	    				Log.error("Error setting attribute value:" + e.getMessage());
	    			} catch (MSystemException e) {
	    				Log.error("Error setting attribute value:" + e.getMessage());
					}
	    		}
    		}
    	}
    }

    /**
     * Checks the attribute for special annotations and reads the corresponding
     * value if such an annotation is present, e. g., <code>@Monitor(value="classname")</code> 
     * for the name of the instance type.
     * @param objRef
     * @param o
     * @param attr
     * @return <code>true</code>, if the attribtue <code>attr</code> has a special value  
     */
    private boolean readSpecialAttributeValue(ObjectReference objRef,
			MObject o, MAttribute attr) {
    	
    	String annotation = attr.getAnnotationValue("Monitor", "value");
    	if (annotation.equals("")) return false;
    	
		Value v = UndefinedValue.instance; 
		if (annotation.equals("classname")) {
			v = new StringValue(objRef.referenceType().name());
		} else {
			v = new StringValue("undefined special value " + StringUtil.inQuotes(annotation));
		}

		o.state(getSystem().state()).setAttributeValue(attr, v);
		
		return true;
	}

	/**
     * Returns the mapped USE value of <code>javaValue</code>.
     * Java-value of type {@link ObjectReference} are tried to be mapped to
     * corresponding USE-objects. If no object is found <code>UndefinedValue</code>
     * is returned.
     * @param javaValue
     * @return
     */
    private Value getUSEValue(com.sun.jdi.Value javaValue, boolean shouldBeUSEObject) {
    	Value v = UndefinedValue.instance;
		
    	if (javaValue == null) return v;
    	
    	if (shouldBeUSEObject) {
    		if (javaValue instanceof ObjectReference) {
    			MObject useObject = instanceMapping.get(((ObjectReference)javaValue));
    			if (useObject != null)
    				v = new ObjectValue(useObject.type(), useObject);
    			else
    				Log.warn("USE object for Java value " + javaValue.toString() + " not found!");
    		}
		} else if (javaValue instanceof StringReference) {
			v = new StringValue(((StringReference)javaValue).value());
		} else if (javaValue instanceof IntegerValue) {
			v = new org.tzi.use.uml.ocl.value.IntegerValue(((IntegerValue)javaValue).intValue());
		} else if (javaValue instanceof BooleanValue) {
			boolean b = ((BooleanValue)javaValue).booleanValue();
			v = org.tzi.use.uml.ocl.value.BooleanValue.get(b);
		} else {
			System.out.println("Unhandled type:" + javaValue.getClass().getName());
		}
		
		return v;
    }
    
	private void readLinks(ObjectReference objRef, MObject o) {
		for (MAssociation ass : o.cls().allAssociations()) {
    		if (ass instanceof MAssociationClass) {
    			
    		} else {
    			MClass cls = o.cls();
    			//TODO: Multiple inheritance or better way
    			List<MNavigableElement> reachableEnds = null;
    			
    			while (cls != null) {
    				try {
    					reachableEnds = ass.navigableEndsFrom(cls);
    					break;
    				} catch (IllegalArgumentException e) {
    					Iterator<MClass> parentIter = cls.parents().iterator(); 
    					if (parentIter.hasNext())
    						cls = cls.parents().iterator().next();
    					else
    						break;
    				}
    			}
    			
    			if (reachableEnds == null) continue;
    			
    			// Check if object has link in java vm
        		for (MNavigableElement reachableElement : reachableEnds) {
        			MAssociationEnd reachableEnd = (MAssociationEnd)reachableElement;
        			if (reachableEnd.multiplicity().isCollection()) {
        				readLinks(objRef, o, reachableEnd);
        			} else {
        				readLink(objRef, o, reachableEnd);
        			}
        		}
    		}
    	}
	}
    
    private void readLink(ObjectReference objRef, MObject source, MAssociationEnd end) {
    	Field field = objRef.referenceType().fieldByName(end.nameAsRolename());
    	
    	if (field == null) {
    		return;
    	}
    	
    	// Get the referenced object
    	com.sun.jdi.Value fieldValue = objRef.getValue(field);
    	if (fieldValue instanceof ArrayReference) {
    		ArrayReference arrayRef = (ArrayReference)fieldValue;
    		List<org.tzi.use.uml.ocl.value.Value> qualifierValues;
    		ObjectReference refTarget = null;
    			
    		try {
    			List<com.sun.jdi.Value> arrayValues = arrayRef.getValues();
    			
				for (int index1 = 0; index1 < arrayValues.size(); index1++) {
					com.sun.jdi.Value elementValue = arrayValues.get(index1);
					
					if (elementValue instanceof ArrayReference) {
						List<com.sun.jdi.Value> arrayValues2 = ((ArrayReference)elementValue).getValues();
						for (int index2 = 0; index2 < arrayValues2.size(); index2++) {
							com.sun.jdi.Value elementValue2 = arrayValues2.get(index2);
							
							qualifierValues = new ArrayList<org.tzi.use.uml.ocl.value.Value>(2);
							qualifierValues.add(new org.tzi.use.uml.ocl.value.IntegerValue(index1));
							qualifierValues.add(new org.tzi.use.uml.ocl.value.IntegerValue(index2));
							refTarget = (ObjectReference)elementValue2;
							
							if (refTarget != null) {
					    		MObject target = instanceMapping.get(refTarget);

					    		if (target != null) {
					    			createLink(source, end, target, qualifierValues);
					    		}
					    	}							
						}
					} else {
						refTarget = (ObjectReference)elementValue;
						if (refTarget != null) {
							qualifierValues = new ArrayList<org.tzi.use.uml.ocl.value.Value>(1);
							qualifierValues.add(new org.tzi.use.uml.ocl.value.IntegerValue(index1));
							
				    		MObject target = instanceMapping.get(refTarget);

				    		if (target != null) {
				    			createLink(source, end, target, qualifierValues);
				    		}
				    	}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
    		
    	} else {
	    	ObjectReference referencedObject = (ObjectReference)objRef.getValue(field);
	    	
	    	if (referencedObject != null) {
	    		MObject target = instanceMapping.get(referencedObject);
	    		if (target != null) {
	    			createLink(source, end, target);
	    		}
	    	}
    	}
    }

	private void createLink(MObject source, MAssociationEnd end, MObject target) {
		createLink(source, end, target, Collections.<org.tzi.use.uml.ocl.value.Value>emptyList());
	}
	
	private void createLink(MObject source, MAssociationEnd end, MObject target, 
							List<org.tzi.use.uml.ocl.value.Value> qualifierValues) {
		List<MObject> linkedObjects = new ArrayList<MObject>();
		if (end.association().associationEnds().indexOf(end) == 0) {
			linkedObjects.add(target);
			linkedObjects.add(source);
		} else {
			linkedObjects.add(source);
			linkedObjects.add(target);
		}

		List<List<org.tzi.use.uml.ocl.value.Value>> qv = new ArrayList<List<org.tzi.use.uml.ocl.value.Value>>();
		if (end.association().associationEnds().get(0).hasQualifiers()) {
			qv.add(qualifierValues);
			qv.add(Collections.<org.tzi.use.uml.ocl.value.Value>emptyList());
		} else {
			qv.add(Collections.<org.tzi.use.uml.ocl.value.Value>emptyList());
			qv.add(qualifierValues);
		}
		
		// Maybe the link is already created by reading the other instance
		try {
			if (!getSystem().state().hasLink(end.association(), linkedObjects, qv)) {
				if (useSoil) {
					MLinkInsertionStatement stmt = 
						new MLinkInsertionStatement(end.association(), linkedObjects.toArray(new MObject[2]), qv);
					getSystem().evaluateStatement(stmt);
				} else {
					getSystem().state().createLink(end.association(), linkedObjects, qv);
				}
				++countLinks;
			}
		} catch (MSystemException e) {
			Log.error("Link could not be created! " + e.getMessage());
		}
	}
    
    private void readLinks(ObjectReference objRef, MObject o, MAssociationEnd end) {
    	Field field = objRef.referenceType().fieldByName(end.nameAsRolename());
    	
    	if (field == null) {
    		return;
    	}
    	
    	// Get the referenced objects
    	ObjectReference objects = (ObjectReference)objRef.getValue(field);
    	
    	if (objects == null) {
    		return;
    	}
    	
    	if (objects.type() instanceof ClassType) {
    		ClassType collectionType = (ClassType)objects.type();
    	
    		if (collectionType.name().equals("java.util.HashSet")) {
    			readLinksHashSet(objects, o, end);
    		} else {
    			System.out.println("Association end " + StringUtil.inQuotes(end.toString()) + " is represented by " + collectionType.name());
    		}
    	} else if (objects.type() instanceof ArrayType) {
    		readQualifiedLinks(objects, o, end);
    	}
    	
    }
    
    private void readQualifiedLinks(ObjectReference array, MObject o, MAssociationEnd end) {
    	
    }
    
    private void readLinksHashSet(ObjectReference hashset, MObject o, MAssociationEnd end) {
    	// HashSet uses the private field "map:HashMap" to store the values
    	Field field = hashset.referenceType().fieldByName("map");
    	ObjectReference mapValue = (ObjectReference)hashset.getValue(field);
    	
    	// Values are stored in the field "table:Entry"
    	field = mapValue.referenceType().fieldByName("table");
    	ArrayReference tableValue = (ArrayReference)mapValue.getValue(field);
    	
    	List<com.sun.jdi.Value> mapEntries = tableValue.getValues();
    	Field fieldKey = null;
    	Field fieldNext = null;
    	
    	for (com.sun.jdi.Value value : mapEntries) {
    		if (value != null) {
    			ObjectReference mapEntry = (ObjectReference)value;
    		
    			if (fieldKey == null) {
    				fieldKey = mapEntry.referenceType().fieldByName("key");
    				fieldNext = mapEntry.referenceType().fieldByName("next");
    			}

    			ObjectReference referencedObject = (ObjectReference)mapEntry.getValue(fieldKey);
    			if (instanceMapping.containsKey(referencedObject)) {
    				createLink(o, end, instanceMapping.get(referencedObject));
    			}
    			
    			ObjectReference nextEntry = (ObjectReference)mapEntry.getValue(fieldNext);
    			while (nextEntry != null) {
    				referencedObject = (ObjectReference)nextEntry.getValue(fieldKey);
    				if (instanceMapping.containsKey(referencedObject)) {
        				createLink(o, end, instanceMapping.get(referencedObject));
        			}
    				nextEntry = (ObjectReference)nextEntry.getValue(fieldNext);
    			}
    		}
    	}
    }
    
    private boolean onMethodCall(BreakpointEvent breakpointEvent) {
    	if (breakpointEvent.location().method().isConstructor()) {
    		return handleConstructorCall(breakpointEvent);
    	} else {
    		if (!hasSnapshot()) return true;
    		
    		return handleMethodCall(breakpointEvent);
    	}
    }
    
    private boolean handleConstructorCall(BreakpointEvent breakpointEvent) {
    	Log.debug("onConstructorCall: " + breakpointEvent.location().method().toString());
        	
    	StackFrame currentFrame;
		try {
			ThreadReference thread = breakpointEvent.thread();
			currentFrame = thread.frame(0);
	    	// Check if we are a nested constructor
			
			for (int index = 1; index < breakpointEvent.thread().frameCount(); ++index) {
				if (thread.frame(index).location().method().isConstructor())
					return true;
			}
		} catch (IncompatibleThreadStateException e) {
			Log.error("Could not retrieve stack frame");
			return true;
		}
		
    	ObjectReference javaObject = currentFrame.thisObject();
    	MClass cls = getUSEClass(javaObject);
    	
    	try {
			createInstance(cls, javaObject);
		} catch (MSystemException e) {
			Log.error("USE object for new instance of type " + javaObject.type().name() + " could not be created.");
			return true;
		}
		
		return true;
    }
    
    /**
	 * @param javaObject
	 * @return
	 */
	private MClass getUSEClass(ObjectReference javaObject) {
		// Try to find a class by the default name
		String className = javaObject.type().name();
		className = className.substring(className.lastIndexOf(".") + 1);
		
		Log.debug("getUSEClass: " + className);
		
		MClass cls = getSystem().model().getClass(className);
		
		if (cls == null) {
			// Find class by annotated value
			for (MClass aCls : getSystem().model().classes()) {
				className = aCls.getAnnotationValue("Monitor", "className");
				if (className != "") {
					cls = getSystem().model().getClass(className);
					if (cls != null)
						break;
				}
					
			}
		}
		
		return cls;
	}

	private boolean handleMethodCall(BreakpointEvent breakpointEvent) {
    	String operationName = breakpointEvent.location().method().name();
    	Log.debug("onMethodCall: " + operationName);
    	
    	StackFrame currentFrame;
		try {
			currentFrame = breakpointEvent.thread().frame(0);
		} catch (IncompatibleThreadStateException e) {
			Log.error("Could not retrieve stack frame");
			return true;
		}
		
    	ObjectReference javaObject = currentFrame.thisObject();
    	
    	Value selfValue = getUSEValue(javaObject, true);
    	
    	if (selfValue.isUndefined()) {
    		Log.warn("Could not retrieve this object for operation call!");
    		return true;
    	}
    	
    	MObject self = ((ObjectValue)selfValue).value();
    	MOperation useOperation = self.cls().operation(operationName, true);
    	   	
    	try {
			if (useOperation.allParams().size() != breakpointEvent.location().method().arguments().size()) {
				Log.warn("Wrong number of arguments!");
				return true;
			}
		} catch (AbsentInformationException e) {
			Log.error("Could not validate argument size");
			return true;
		}

    	List<com.sun.jdi.Value> javaArgs = currentFrame.getArgumentValues();
    	Map<String, Expression> arguments = new HashMap<String, Expression>();
    	
    	for (int index = 0; index < useOperation.allParams().size(); index++) {
    		Value val = getUSEValue(javaArgs.get(index), useOperation.allParams().get(index).type().isObjectType());
    		arguments.put(useOperation.allParams().get(index).name(), new ExpressionWithValue(val));
    	}
    	
		MEnterOperationStatement operationCall = new MEnterOperationStatement(
				new ExpObjRef(self), useOperation, arguments, ppcHandler );
    	
    	try {
    		getSystem().evaluateStatement(operationCall);
		} catch (MSystemException e) {
			Log.error(e.getMessage());
			return false;
		}
		
		MethodExitRequest req = monitoredVM.eventRequestManager().createMethodExitRequest();
		req.addInstanceFilter(javaObject);
		req.enable();
		
		return true;
    }
    
    private boolean onMethodExit(MethodExitEvent exitEvent) {
    	if (!exitEvent.method().name().equals(getSystem().getCurrentOperation().getOperation().name()))
    		return true;
    	
    	ExpressionWithValue result = null;
		this.monitoredVM.eventRequestManager().deleteEventRequest(exitEvent.request());
    	
    	if (getSystem().getCurrentOperation().getOperation().hasResultType()) {
			result = new ExpressionWithValue(getUSEValue(
					exitEvent.returnValue(), getSystem().getCurrentOperation()
							.getOperation().resultType().isObjectType()));
		}

		MExitOperationStatement stmt = new MExitOperationStatement(result, ppcHandler);
    	
		try {
			getSystem().evaluateStatement(stmt);
		} catch (MSystemException e) {
			return false;
		}
		
		return true;
    }
    
    protected static MonitorPPCHandler ppcHandler = new MonitorPPCHandler();
    
    protected static class MonitorPPCHandler implements PPCHandler {

		/* (non-Javadoc)
		 * @see org.tzi.use.uml.sys.ppcHandling.PPCHandler#handlePreConditions(org.tzi.use.uml.sys.MSystem, org.tzi.use.uml.sys.MOperationCall)
		 */
		@Override
		public void handlePreConditions(MSystem system,
				MOperationCall operationCall)
				throws PreConditionCheckFailedException {
			Map<MPrePostCondition, Boolean> evaluationResults = 
					operationCall.getPreConditionEvaluationResults();
			
			boolean oneFailed = false;
			
			for (Entry<MPrePostCondition, Boolean> entry : evaluationResults.entrySet()) {
				MPrePostCondition preCondition = entry.getKey();
				if (!entry.getValue().booleanValue()) {
					Log.warn(
						"Precondition " + 
						StringUtil.inQuotes(preCondition.name()) + 
						" failed!");
					
					oneFailed = true;
				}
			}
			
			if (oneFailed) {
				throw new PreConditionCheckFailedException(operationCall);
			}
			
		}

		/* (non-Javadoc)
		 * @see org.tzi.use.uml.sys.ppcHandling.PPCHandler#handlePostConditions(org.tzi.use.uml.sys.MSystem, org.tzi.use.uml.sys.MOperationCall)
		 */
		@Override
		public void handlePostConditions(MSystem system,
				MOperationCall operationCall)
				throws PostConditionCheckFailedException {
			boolean oneFailed = false;
			
			Map<MPrePostCondition, Boolean> evaluationResults = 
				operationCall.getPostConditionEvaluationResults();
			
			for (Entry<MPrePostCondition, Boolean> entry : evaluationResults.entrySet()) {
				MPrePostCondition preCondition = entry.getKey();
				if (!entry.getValue().booleanValue()) {
					Log.warn(
						"Postcondition " + 
						StringUtil.inQuotes(preCondition.name()) + 
						" failed!");
					
					oneFailed = true;
				}
			}
			
			if (oneFailed) {
				throw new PostConditionCheckFailedException(operationCall);
			}
			
		}
    }
    
    public void addStateChangedListener(IMonitorStateListener listener) {
    	this.stateListener.add(listener);
    }
    
    public void removeStateChangedListener(IMonitorStateListener listener) {
    	this.stateListener.remove(listener);
    }
    
    protected void onMonitorStart() {
    	for (IMonitorStateListener listener : stateListener) {
    		listener.monitorStarted(this);
    		listener.monitorStateChanged(this);
    	}
    }
    
    protected void onMonitorPause() {
    	for (IMonitorStateListener listener : stateListener) {
    		listener.monitorPaused(this);
    		listener.monitorStateChanged(this);
    	}
    }
    
    protected void onMonitorResume() {
    	for (IMonitorStateListener listener : stateListener) {
    		listener.monitorResumed(this);
    		listener.monitorStateChanged(this);
    	}
    }
    
    protected void onMonitorEnd() {
    	for (IMonitorStateListener listener : stateListener) {
    		listener.monitorEnded(this);
    		listener.monitorStateChanged(this);
    	}
    }

    public void addSnapshotProgressListener(IProgressListener listener) {
    	this.snapshotProgressListener.add(listener);
    }
    
    public void removeSnapshotProgressListener(IProgressListener listener) {
    	this.snapshotProgressListener.remove(listener);
    }
    
    protected void onSnapshotStart(String description, int numClasses) {
    	ProgressArgs args = new ProgressArgs(description, numClasses);
    	for (IProgressListener listener : this.snapshotProgressListener) {
    		listener.progressStart(args);
    	}
    }
    
    protected void onSnapshotProgress(ProgressArgs args) {
    	for (IProgressListener listener : this.snapshotProgressListener) {
    		listener.progress(args);
    	}
    }
    
    protected void onSnapshotEnd() {
    	for (IProgressListener listener : this.snapshotProgressListener) {
    		listener.progressEnd();
    	}
    }
    
	@Override
	public void stateChanged(ChangeEvent e) {
		if (!isResetting && isRunning()) {
			end();
		}
	}
}
