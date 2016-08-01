/*******************************************************************************
 * AIOLOS  - Framework for dynamic distribution of software components at runtime.
 * Copyright (C) 2014-2016  iMinds - IBCN - UGent
 *
 * This file is part of AIOLOS.
 *
 * AIOLOS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     Tim Verbelen, Steven Bohez, Elias Deconinck
 *******************************************************************************/
package be.iminds.aiolos.rsa;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Hashtable;
import java.util.List;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.ImportReference;

import be.iminds.aiolos.rsa.exception.ROSGiException;
import be.iminds.aiolos.rsa.network.api.MessageSender;
import be.iminds.aiolos.rsa.network.api.NetworkChannel;
import be.iminds.aiolos.rsa.network.api.NetworkChannelFactory;
import be.iminds.aiolos.rsa.network.message.RemoteCallMessage;
import be.iminds.aiolos.rsa.network.message.RemoteCallResultMessage;
import be.iminds.aiolos.rsa.util.MethodSignature;
import be.iminds.aiolos.rsa.util.URI;

/**
 * Proxy object at the client side that calls the remote service.
 * 
 * A dynamic proxy object is generated that dispatches the calls over the network.
 */
public class ROSGiProxy implements InvocationHandler, ImportReference{

	private ServiceRegistration<?> registration;
	private EndpointDescription endpointDescription;

	private String serviceId;
	private NetworkChannel channel;
	private MessageSender sender;
	
	private int refCount = 0;
	
	private ROSGiProxy(EndpointDescription endpointDescription, NetworkChannel channel, MessageSender sender){
		this.endpointDescription = endpointDescription;
		this.serviceId = ""+endpointDescription.getServiceId();
		this.channel = channel;
		this.sender = sender;
	}
	
	public static ROSGiProxy createServiceProxy(BundleContext context, ClassLoader loader, EndpointDescription endpointDescription, NetworkChannelFactory channelFactory, MessageSender sender) throws ROSGiException{
		String endpointId = endpointDescription.getId();
		List<String> interfaces = endpointDescription.getInterfaces();

		URI uri = new URI(endpointId);
		NetworkChannel channel;
		
		try {
			channel = channelFactory.getChannel(uri);
		} catch(Exception e){
			throw new ROSGiException("Error creating service proxy with null channel", e);
		}
		
		ROSGiProxy p = new ROSGiProxy(endpointDescription, channel, sender);
		try {
			Class<?>[] clazzes = new Class[interfaces.size()];
			String[] clazzNames = new String[interfaces.size()];
			for(int i=0;i<interfaces.size();i++){
				clazzNames[i] = interfaces.get(i);
				clazzes[i] = loader.loadClass(interfaces.get(i));
			}
			Object proxy = Proxy.newProxyInstance(loader, clazzes, p);
			Hashtable<String, Object> properties = p.buildServiceProperties();
			p.registration = context.registerService(clazzNames, proxy, properties);
		} catch (ClassNotFoundException e) {
			throw new ROSGiException("Error loading class of service proxy", e);
		}
		return p;
	}
	
	@Override
	public Object invoke(Object proxy, Method method, Object[] args)
			throws Throwable {
		RemoteCallMessage invokeMsg = new RemoteCallMessage(serviceId, MethodSignature.getMethodSignature(method), args);
	
		// equals and hashcode should be invoked on proxy object
		// this enables to keep proxies in a list/map
		if(method.getName().equals("equals")){
			return this.equals(args[0]);
		} else if(method.getName().equals("hashCode")){
			return this.hashCode();
		} else if(method.getName().equals("toString")){
			return "ROSGi proxy for endpoint "+endpointDescription.getId();
		}
		
		try {
			// send the message and get a RemoteCallResultMessage in return
			RemoteCallResultMessage resultMsg = (RemoteCallResultMessage) sender.sendAndWaitMessage(invokeMsg, channel);
			if (resultMsg.causedException()) {
				throw resultMsg.getException();
			}
			Object result = resultMsg.getResult();
			return result;
			
		} catch (ROSGiException e) {
			// Throw exception to the application... remote call failed!
			throw new ServiceException("Error in remote method call "+method.getName()+" of "+endpointDescription.getId(), ServiceException.REMOTE, e);
		}
	}

	
	public int acquire(){
		return ++refCount;
	}
	
	public int release(){
		return --refCount;
	}
	
	public void unregister(){
		if(registration!=null){
			synchronized(registration){
				if(registration!=null){
					try {
						registration.unregister();
					}catch(IllegalStateException e){
						// was already unregistred (e.g. by stopping framework)
					}
					registration = null;
				}
			}
	
		}
	}

	public NetworkChannel getNetworkChannel(){
		return channel;
	}
	
	@Override
	public ServiceReference<?> getImportedService() {
		return registration.getReference();
	}

	@Override
	public EndpointDescription getImportedEndpoint() {
		return endpointDescription;
	}
	
	private Hashtable<String, Object> buildServiceProperties(){
		Hashtable<String, Object> properties = new Hashtable<String, Object>();
		properties.put("service.imported", "true");
		// TODO filter endpointdescription properties?
		for(String key : endpointDescription.getProperties().keySet()){
			if(key!=null && endpointDescription.getProperties().get(key)!=null){
				properties.put(key, endpointDescription.getProperties().get(key));
			}
		}
		return properties;
	}
	
	public String toString(){
		return "Proxy of "+endpointDescription.getId();
	}
}