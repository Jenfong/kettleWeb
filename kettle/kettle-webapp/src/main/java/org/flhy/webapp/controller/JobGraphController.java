package org.flhy.webapp.controller;

import java.net.InetAddress;
import java.net.URLDecoder;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.flhy.ext.App;
import org.flhy.ext.JobExecutor;
import org.flhy.ext.PluginFactory;
import org.flhy.ext.base.GraphCodec;
import org.flhy.ext.job.JobExecutionConfigurationCodec;
import org.flhy.ext.job.step.JobEntryEncoder;
import org.flhy.ext.utils.JSONArray;
import org.flhy.ext.utils.JSONObject;
import org.flhy.ext.utils.StringEscapeHelper;
import org.flhy.webapp.utils.JsonUtils;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.RowMetaAndData;
import org.pentaho.di.core.logging.DefaultLogLevel;
import org.pentaho.di.core.plugins.JobEntryPluginType;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.job.JobExecutionConfiguration;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entries.ftp.JobEntryFTP;
import org.pentaho.di.job.entries.ftpdelete.JobEntryFTPDelete;
import org.pentaho.di.job.entries.ftpput.JobEntryFTPPUT;
import org.pentaho.di.job.entries.sftp.JobEntrySFTP;
import org.pentaho.di.job.entries.sftp.SFTPClient;
import org.pentaho.di.job.entries.special.JobEntrySpecial;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.job.entry.JobEntryInterface;
import org.pentaho.di.repository.ObjectId;
import org.pentaho.di.repository.Repository;
import org.pentaho.di.repository.RepositorySecurityProvider;
import org.pentaho.di.ui.job.entries.ftpput.JobEntryFTPPUTDialog;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.w3c.dom.Element;

import com.enterprisedt.net.ftp.FTPClient;
import com.mxgraph.util.mxUtils;

@Controller
@RequestMapping(value="/job")
public class JobGraphController {
	
	@ResponseBody
	@RequestMapping(method=RequestMethod.POST, value="/engineXml")
	protected void engineXml(@RequestParam String graphXml) throws Exception {
		GraphCodec codec = (GraphCodec) PluginFactory.getBean(GraphCodec.JOB_CODEC);
		JobMeta jobMeta = (JobMeta) codec.decode(graphXml);
		String xml = XMLHandler.getXMLHeader() + jobMeta.getXML();
		
		JsonUtils.responseXml(xml);
	}
	
	@ResponseBody
	@RequestMapping(method=RequestMethod.POST, value="/entries")
	protected void entries(@RequestParam String graphXml) throws Exception {
		GraphCodec codec = (GraphCodec) PluginFactory.getBean(GraphCodec.JOB_CODEC);
		JobMeta jobMeta = (JobMeta) codec.decode(graphXml);
		JSONArray jsonArray = new JSONArray();
		for (int i = 0; i < jobMeta.getJobCopies().size(); i++) {
			JobEntryCopy copy = jobMeta.getJobCopies().get(i);
			JSONObject jsonObject = new JSONObject();
			jsonObject.put("name", copy.getName() + (copy.getNr() > 0 ? copy.getNr() : ""));
			jsonArray.add(jsonObject);
		}

		JsonUtils.response(jsonArray);
	}
	
	@ResponseBody
	@RequestMapping(method=RequestMethod.POST, value="/save")
	protected void save(@RequestParam String graphXml) throws Exception {
		GraphCodec codec = (GraphCodec) PluginFactory.getBean(GraphCodec.JOB_CODEC);
		JobMeta jobMeta = (JobMeta) codec.decode(graphXml);
		Repository repository = App.getInstance().getRepository();
		ObjectId existingId = repository.getTransformationID( jobMeta.getName(), jobMeta.getRepositoryDirectory() );
		if(jobMeta.getCreatedDate() == null)
			jobMeta.setCreatedDate(new Date());
		if(jobMeta.getObjectId() == null)
			jobMeta.setObjectId(existingId);
		jobMeta.setModifiedDate(new Date());
		
		 boolean versioningEnabled = true;
         boolean versionCommentsEnabled = true;
         String fullPath = jobMeta.getRepositoryDirectory() + "/" + jobMeta.getName() + jobMeta.getRepositoryElementType().getExtension(); 
         RepositorySecurityProvider repositorySecurityProvider = repository.getSecurityProvider() != null ? repository.getSecurityProvider() : null;
         if ( repositorySecurityProvider != null ) {
        	 versioningEnabled = repositorySecurityProvider.isVersioningEnabled( fullPath );
        	 versionCommentsEnabled = repositorySecurityProvider.allowsVersionComments( fullPath );
         }
		String versionComment = null;
		if (!versioningEnabled || !versionCommentsEnabled) {
			versionComment = "";
		} else {
			versionComment = "no comment";
		}
		
		repository.save( jobMeta, versionComment, null);
		
		JsonUtils.success("作业保存成功！");
	}
	
	/**
	 * 新建环节
	 * 
	 * @param graphXml
	 * @param stepId
	 * @param stepName
	 * @throws Exception
	 */
	@ResponseBody
	@RequestMapping(method=RequestMethod.POST, value="/newJobEntry")
	protected void newJobEntry(@RequestParam String graphXml, @RequestParam String pluginId, @RequestParam String name) throws Exception {
		GraphCodec codec = (GraphCodec) PluginFactory.getBean(GraphCodec.JOB_CODEC);
		JobMeta jobMeta = (JobMeta) codec.decode(graphXml);
		
		PluginRegistry registry = PluginRegistry.getInstance();
		PluginInterface jobPlugin = registry.findPluginWithId(JobEntryPluginType.class, pluginId);

		if (jobPlugin != null) {
			// Determine name & number for this entry.
	        String basename = URLDecoder.decode(name, "utf-8");

	        // See if the name is already used...
	        //
	        String entry_name = basename;
	        int nr = 2;
	        JobEntryCopy check = jobMeta.findJobEntry( entry_name, 0, true );
			while (check != null) {
				entry_name = basename + " " + nr++;
				check = jobMeta.findJobEntry(entry_name, 0, true);
			}

	        // Generate the appropriate class...
			JobEntryInterface jei = (JobEntryInterface) registry.loadClass(jobPlugin);
			jei.setPluginId(jobPlugin.getIds()[0]);
			jei.setName(entry_name);

			if (jei.isSpecial()) {
				if (JobMeta.STRING_SPECIAL_START.equals(jei.getName())) {
					// Check if start is already on the canvas...
					if (jobMeta.findStart() != null) {
						return;
					}
					((JobEntrySpecial) jei).setStart(true);
					jei.setName(JobMeta.STRING_SPECIAL_START);
				}
				if (JobMeta.STRING_SPECIAL_DUMMY.equals(jei.getName())) {
					((JobEntrySpecial) jei).setDummy(true);
					// jei.setName(JobMeta.STRING_SPECIAL_DUMMY); // Don't
					// overwrite the name
				}
			}
			
			JobEntryCopy jge = new JobEntryCopy();
			jge.setEntry(jei);
			jge.setNr(0);
			jge.setDrawn();
			
			JobEntryEncoder encoder = (JobEntryEncoder) PluginFactory.getBean(jei.getPluginId());
			Element e = encoder.encodeStep(jge);
			JsonUtils.responseXml(XMLHandler.getXMLHeader() + mxUtils.getXml(e));
		}
	}
	
	/**
	 * 新建hop
	 * 
	 * @param graphXml
	 * @param stepId
	 * @param stepName
	 * @throws Exception
	 */
	@ResponseBody
	@RequestMapping(method=RequestMethod.POST, value="/newHop")
	protected void newHop(@RequestParam String graphXml, @RequestParam String fromLabel, @RequestParam String toLabel) throws Exception {
		GraphCodec codec = (GraphCodec) PluginFactory.getBean(GraphCodec.JOB_CODEC);
		JobMeta jobMeta = (JobMeta) codec.decode(graphXml);
		
		JobEntryCopy fr = jobMeta.findJobEntry(URLDecoder.decode(fromLabel, "utf-8"));
		JobEntryCopy to = jobMeta.findJobEntry(URLDecoder.decode(toLabel, "utf-8"));
		
		System.out.println(fr);
		System.out.println(to);
	}
	
	@ResponseBody
	@RequestMapping(method=RequestMethod.POST, value="/sftptest")
	protected void sftptest(@RequestParam String graphXml, @RequestParam String stepName) throws Exception {
		GraphCodec codec = (GraphCodec) PluginFactory.getBean(GraphCodec.JOB_CODEC);
		JobMeta jobMeta = (JobMeta) codec.decode(graphXml);
		
		JobEntryCopy jobEntryCopy = jobMeta.findJobEntry(stepName);
		JobEntrySFTP sftp = (JobEntrySFTP) jobEntryCopy.getEntry();
		 
		String info = "";
		SFTPClient sftpclient = null;
		try {
			String servername = jobMeta.environmentSubstitute(sftp.getServerName());
			String serverport = jobMeta.environmentSubstitute(sftp.getServerPort());
			String username = jobMeta.environmentSubstitute(sftp.getUserName());
			String password = jobMeta.environmentSubstitute(sftp.getPassword());
			String keyFilename = jobMeta.environmentSubstitute(sftp.getKeyFilename());
			String keyFilePass = jobMeta.environmentSubstitute(sftp.getKeyPassPhrase());

			sftpclient = new SFTPClient(InetAddress.getByName(servername), Const.toInt(serverport, 22), username, keyFilename, keyFilePass);
			String proxyHost = jobMeta.environmentSubstitute(sftp.getProxyHost());
			String proxyPort = jobMeta.environmentSubstitute(sftp.getProxyPort());
			String proxyUsername = jobMeta.environmentSubstitute(sftp.getProxyUsername());
			String proxyPass = jobMeta.environmentSubstitute(sftp.getProxyPassword());
			String proxyType = jobMeta.environmentSubstitute(sftp.getProxyType());
			if (!Const.isEmpty(proxyHost)) {
				sftpclient.setProxy(proxyHost, proxyPort, proxyUsername, proxyPass, proxyType);
			}
			sftpclient.login(password);

			JsonUtils.success(BaseMessages.getString( JobEntrySFTP.class, "JobSFTP.Connected.Title.Ok" ), 
					BaseMessages.getString( JobEntrySFTP.class, "JobSFTP.Connected.OK", sftp.getServerName() ) + Const.CR);
			return;
		} catch (Exception e) {
			if (sftpclient != null) {
				try {
					sftpclient.disconnect();
				} catch (Exception ignored) {
				}
				sftpclient = null;
			}
			info = e.getMessage();
		}
		
		JsonUtils.fail(BaseMessages.getString( JobEntrySFTP.class, "JobSFTP.ErrorConnect.Title.Bad" ), 
				 BaseMessages.getString( JobEntrySFTP.class, "JobSFTP.ErrorConnect.NOK", sftp.getServerName(), info) + Const.CR);
	}
	@ResponseBody
	@RequestMapping(method=RequestMethod.POST,value="/ftpDownlond")
	protected void ftpDownlond(@RequestParam String graphXml,@RequestParam String stepName) throws Exception{
		GraphCodec codec = (GraphCodec) PluginFactory.getBean(GraphCodec.JOB_CODEC);
		JobMeta jobmeta = (JobMeta) codec.decode(graphXml);
		
		JobEntryCopy jobEntryCopy = jobmeta.findJobEntry(stepName);
		JobEntryFTP ftp = (JobEntryFTP) jobEntryCopy.getEntry();
		
		String info = "";
		FTPClient ftpClient = null;
		try{
			String servername = jobmeta.environmentSubstitute(ftp.getServerName());
			String port = jobmeta.environmentSubstitute(ftp.getPort());
			String username = jobmeta.environmentSubstitute(ftp.getUserName());
			String password = jobmeta.environmentSubstitute(ftp.getPassword());
			
			ftpClient = new FTPClient(servername,Integer.parseInt(port));
			
			String proxyHost = jobmeta.environmentSubstitute(ftp.getProxyHost());
			String proxyPort = jobmeta.environmentSubstitute(ftp.getProxyPort());
			String proxyUsername = jobmeta.environmentSubstitute(ftp.getProxyUsername());
			String proxtPassword = jobmeta.environmentSubstitute(ftp.getProxyPassword());
			
			ftpClient.login(username, password);
			
			JsonUtils.success(BaseMessages.getString(JobEntryFTP.class,"JobFTP.Connected.Title.Ok"),
					BaseMessages.getString(JobEntryFTP.class,"JobFTP.Connected.OK",ftp.getUserName()+Const.CR));

			return;
		}catch(Exception e){
			if(ftpClient!=null){
				try{
				ftpClient.quit();
				}catch(Exception ex){
					
				}
				ftpClient= null;
			}
			info = e.getMessage();
		}
		JsonUtils.fail(BaseMessages.getString( JobEntryFTP.class, "JobFTP.ErrorConnect.Title.Bad" ), 
				 BaseMessages.getString( JobEntryFTP.class, "JobFTP.ErrorConnect.NOK", ftp.getServerName(), info) + Const.CR);
	}
	@ResponseBody
	@RequestMapping(method=RequestMethod.POST, value="/ftptest")
	protected void ftpputtest(@RequestParam String graphXml, @RequestParam String stepName) throws Exception {
		GraphCodec codec = (GraphCodec) PluginFactory.getBean(GraphCodec.JOB_CODEC);
		JobMeta jobMeta = (JobMeta) codec.decode(graphXml);
		
		JobEntryCopy jobEntryCopy = jobMeta.findJobEntry(stepName);
		String info = "";
		String servername = "";
		String serverport = "";
		String username = "";
		String password = "";
		String proxyHost = "";
		String proxyPort = "";
		String proxyUsername = "";
		String proxyPass = "";
		String remoteDirectory="";
		FTPClient ftpputClient = null;
		try {
			 if("FTP 上传".equals(stepName)){
					JobEntryFTPPUT ftpput = (JobEntryFTPPUT) jobEntryCopy.getEntry();
					servername = jobMeta.environmentSubstitute(ftpput.getServerName());
					serverport = jobMeta.environmentSubstitute(ftpput.getServerPort());
					username = jobMeta.environmentSubstitute(ftpput.getUserName());
				    password = jobMeta.environmentSubstitute(ftpput.getPassword());
					 proxyHost = jobMeta.environmentSubstitute(ftpput.getProxyHost());
					 proxyPort = jobMeta.environmentSubstitute(ftpput.getProxyPort());
					 proxyUsername = jobMeta.environmentSubstitute(ftpput.getProxyUsername());
					 proxyPass = jobMeta.environmentSubstitute(ftpput.getProxyPassword());
					 remoteDirectory=jobMeta.environmentSubstitute(ftpput.getRemoteDirectory());
			 }
			 if("FTP 删除".equals(stepName)){
				 JobEntryFTPDelete ftpdelete = (JobEntryFTPDelete) jobEntryCopy.getEntry();
					servername = jobMeta.environmentSubstitute(ftpdelete.getServerName());
					serverport = jobMeta.environmentSubstitute(ftpdelete.getPort());
					username = jobMeta.environmentSubstitute(ftpdelete.getUserName());
				    password = jobMeta.environmentSubstitute(ftpdelete.getPassword());
					 proxyHost = jobMeta.environmentSubstitute(ftpdelete.getProxyHost());
					 proxyPort = jobMeta.environmentSubstitute(ftpdelete.getProxyPort());
					 proxyUsername = jobMeta.environmentSubstitute(ftpdelete.getProxyUsername());
					 proxyPass = jobMeta.environmentSubstitute(ftpdelete.getProxyPassword());
					 remoteDirectory=jobMeta.environmentSubstitute(ftpdelete.getFtpDirectory());

			 }
			
			ftpputClient = new FTPClient(servername, Integer.parseInt(serverport) );
		
			ftpputClient.login(username, password);
			if("FTP 上传".equals(stepName)){
			JsonUtils.success(BaseMessages.getString( JobEntryFTPPUT.class, "JobFTPPUT.Connected.Title.Ok" ), 
					BaseMessages.getString( JobEntryFTPPUT.class, "JobFTPPUT.Connected.OK", servername ) + Const.CR);
			return;
			}
			if("FTP 删除".equals(stepName)){
				JsonUtils.success(BaseMessages.getString( JobEntryFTPDelete.class, "JobFTPDelete.Connected.Title.Ok" ), 
						BaseMessages.getString( JobEntryFTPDelete.class, "JobFTPDelete.Connected.OK", servername ) + Const.CR);
				return;
			}
		} catch (Exception e) {
			if (ftpputClient != null) {
				try {
					ftpputClient.quit();
				} catch (Exception ignored) {
				}
				ftpputClient = null;
			}
			info = e.getMessage();
		}
		if("FTP 上传".equals(stepName)){
		JsonUtils.fail(BaseMessages.getString( JobEntryFTPPUT.class, "JobFTPPUT.ErrorConnect.Title.Bad" ), 
				 BaseMessages.getString( JobEntryFTPPUT.class, "JobFTPPUT.ErrorConnect.NOK", servername, info) + Const.CR);
		}
		if("FTP 删除".equals(stepName)){
			JsonUtils.fail(BaseMessages.getString( JobEntryFTPDelete.class, "JobFTPDelete.ErrorConnect.Title.Bad" ), 
					 BaseMessages.getString( JobEntryFTPDelete.class, "JobFTPDelete.ErrorConnect.NOK", servername, info) + Const.CR);
		}
	}
	
	
	@ResponseBody
	@RequestMapping(method=RequestMethod.POST, value="/ftpdirtest")
	protected void ftpputtestremotedir(@RequestParam String graphXml, @RequestParam String stepName) throws Exception {
		GraphCodec codec = (GraphCodec) PluginFactory.getBean(GraphCodec.JOB_CODEC);
		JobMeta jobMeta = (JobMeta) codec.decode(graphXml);
		
		JobEntryCopy jobEntryCopy = jobMeta.findJobEntry(stepName);
		String servername = "";
		String serverport = "";
		String username = "";
		String password = "";
		String proxyHost = "";
		String proxyPort = "";
		String proxyUsername = "";
		String proxyPass = "";
		String remoteDirectory="";
		

		String info = "";
		FTPClient ftpputClient = null;
		try {
			 if("FTP 上传".equals(stepName)){
					JobEntryFTPPUT ftpput = (JobEntryFTPPUT) jobEntryCopy.getEntry();
					servername = jobMeta.environmentSubstitute(ftpput.getServerName());
					serverport = jobMeta.environmentSubstitute(ftpput.getServerPort());
					username = jobMeta.environmentSubstitute(ftpput.getUserName());
				    password = jobMeta.environmentSubstitute(ftpput.getPassword());
					 proxyHost = jobMeta.environmentSubstitute(ftpput.getProxyHost());
					 proxyPort = jobMeta.environmentSubstitute(ftpput.getProxyPort());
					 proxyUsername = jobMeta.environmentSubstitute(ftpput.getProxyUsername());
					 proxyPass = jobMeta.environmentSubstitute(ftpput.getProxyPassword());
					 remoteDirectory=jobMeta.environmentSubstitute(ftpput.getRemoteDirectory());
			 }
			 if("FTP 删除".equals(stepName)){
				 JobEntryFTPDelete ftpdelete = (JobEntryFTPDelete) jobEntryCopy.getEntry();
					servername = jobMeta.environmentSubstitute(ftpdelete.getServerName());
					serverport = jobMeta.environmentSubstitute(ftpdelete.getPort());
					username = jobMeta.environmentSubstitute(ftpdelete.getUserName());
				    password = jobMeta.environmentSubstitute(ftpdelete.getPassword());
					 proxyHost = jobMeta.environmentSubstitute(ftpdelete.getProxyHost());
					 proxyPort = jobMeta.environmentSubstitute(ftpdelete.getProxyPort());
					 proxyUsername = jobMeta.environmentSubstitute(ftpdelete.getProxyUsername());
					 proxyPass = jobMeta.environmentSubstitute(ftpdelete.getProxyPassword());
					 remoteDirectory=jobMeta.environmentSubstitute(ftpdelete.getFtpDirectory());

			 }

//			String keyFilename = jobMeta.environmentSubstitute(ftpput.getKeyFilename());
//			String keyFilePass = jobMeta.environmentSubstitute(ftpput.getKeyPassPhrase());
			
			ftpputClient = new FTPClient(servername, Integer.parseInt(serverport) );
//			ftpputClient=new FTPClient(remoteAddr, controlPort);
//			ftpputClient = new FTPClient(InetAddress.getByName(servername), Const.toInt(serverport, 21), 30, ftpput.getControlEncoding());
//			ftpputClient = new FTPClient  (InetAddress.getByName(servername), Const.toInt(serverport, 22), username);
	
			ftpputClient.login(username, password);
			if("FTP 上传".equals(stepName)){
			   if(ftpputClient.exists(remoteDirectory)) {
				JsonUtils.success(BaseMessages.getString( JobEntryFTPPUT.class, "JobFTPPUT.FolderExists.OK" ), 
						BaseMessages.getString( JobEntryFTPPUT.class, "JobFTPPUT.FolderExists.OK", remoteDirectory) + Const.CR);
				return;
			  }
			}
			
			if("FTP 删除".equals(stepName)){
				   if(ftpputClient.exists(remoteDirectory)) {
					JsonUtils.success(BaseMessages.getString( JobEntryFTPDelete.class, "JobFTPDelete.FolderExists.OK" ), 
							BaseMessages.getString( JobEntryFTPDelete.class, "JobFTPDelete.FolderExists.Ok", remoteDirectory) + Const.CR);
					return;
				  }
			}
		} catch (Exception e) {
			if (ftpputClient != null) {
				try {
					ftpputClient.quit();
				} catch (Exception ignored) {
				}
				ftpputClient = null;
			}
			info = e.getMessage();
		}
		if("FTP 上传".equals(stepName)){
		     JsonUtils.fail(BaseMessages.getString( JobEntryFTPPUT.class, "JobFTPPUT.FolderExists.Title.Bad" ), 
				 BaseMessages.getString( JobEntryFTPPUT.class, "JobFTPPUT.FolderExists.NOK", remoteDirectory, info) + Const.CR);
		}
		if("FTP 删除".equals(stepName)){
		     JsonUtils.fail(BaseMessages.getString( JobEntryFTPDelete.class, "JobFTPDelete.FolderExists.Title.Bad" ), 
				 BaseMessages.getString( JobEntryFTPDelete.class, "JobFTPDelete.FolderExists.NOK", remoteDirectory, info) + Const.CR);
		}
	}
	
	
	@ResponseBody
	@RequestMapping(method=RequestMethod.POST, value="/sftpdirtest")
	protected void sftpdirtest(@RequestParam String graphXml, @RequestParam String stepName) throws Exception {
		GraphCodec codec = (GraphCodec) PluginFactory.getBean(GraphCodec.JOB_CODEC);
		JobMeta jobMeta = (JobMeta) codec.decode(graphXml);
		
		JobEntryCopy jobEntryCopy = jobMeta.findJobEntry(stepName);
		JobEntrySFTP sftp = (JobEntrySFTP) jobEntryCopy.getEntry();
		 
		String info = "";
		SFTPClient sftpclient = null;
		try {
			String servername = jobMeta.environmentSubstitute(sftp.getServerName());
			String serverport = jobMeta.environmentSubstitute(sftp.getServerPort());
			String username = jobMeta.environmentSubstitute(sftp.getUserName());
			String password = jobMeta.environmentSubstitute(sftp.getPassword());
			String keyFilename = jobMeta.environmentSubstitute(sftp.getKeyFilename());
			String keyFilePass = jobMeta.environmentSubstitute(sftp.getKeyPassPhrase());

			sftpclient = new SFTPClient(InetAddress.getByName(servername), Const.toInt(serverport, 22), username, keyFilename, keyFilePass);
			String proxyHost = jobMeta.environmentSubstitute(sftp.getProxyHost());
			String proxyPort = jobMeta.environmentSubstitute(sftp.getProxyPort());
			String proxyUsername = jobMeta.environmentSubstitute(sftp.getProxyUsername());
			String proxyPass = jobMeta.environmentSubstitute(sftp.getProxyPassword());
			String proxyType = jobMeta.environmentSubstitute(sftp.getProxyType());
			if (!Const.isEmpty(proxyHost)) {
				sftpclient.setProxy(proxyHost, proxyPort, proxyUsername, proxyPass, proxyType);
			}
			sftpclient.login(password);

			if(sftpclient.folderExists(sftp.getScpDirectory())) {
				JsonUtils.success(BaseMessages.getString( JobEntrySFTP.class, "JobSFTP.FolderExists.Title.Ok" ), 
						BaseMessages.getString( JobEntrySFTP.class, "JobSFTP.FolderExists.OK", sftp.getScpDirectory() ) + Const.CR);
				return;
			}
		} catch (Exception e) {
			if (sftpclient != null) {
				try {
					sftpclient.disconnect();
				} catch (Exception ignored) {
				}
				sftpclient = null;
			}
			info = e.getMessage();
		}
		
		JsonUtils.fail(BaseMessages.getString( JobEntrySFTP.class, "JobSFTP.ErrorConnect.Title.Bad" ), 
				 BaseMessages.getString( JobEntrySFTP.class, "JobSFTP.ErrorConnect.NOK", sftp.getServerName(), info) + Const.CR);
	}
	
	@ResponseBody
	@RequestMapping(method=RequestMethod.POST, value="/initRun")
	protected void initRun(@RequestParam String graphXml) throws Exception {
		GraphCodec codec = (GraphCodec) PluginFactory.getBean(GraphCodec.JOB_CODEC);
		JobMeta jobMeta = (JobMeta) codec.decode(graphXml);
		
		JobExecutionConfiguration executionConfiguration = App.getInstance().getJobExecutionConfiguration();
		
		 // Remember the variables set previously
	    //
		RowMetaAndData variables = App.getInstance().getVariables();
	    Object[] data = variables.getData();
	    String[] fields = variables.getRowMeta().getFieldNames();
	    Map<String, String> variableMap = new HashMap<String, String>();
	    for ( int idx = 0; idx < fields.length; idx++ ) {
	    	variableMap.put( fields[idx], data[idx].toString() );
	    }

	    executionConfiguration.setVariables( variableMap );
	    executionConfiguration.getUsedVariables( jobMeta );
	    executionConfiguration.setReplayDate( null );
	    executionConfiguration.setRepository( App.getInstance().getRepository() );
	    executionConfiguration.setSafeModeEnabled( false );
	    executionConfiguration.setStartCopyName( null );
	    executionConfiguration.setStartCopyNr( 0 );

	    executionConfiguration.setLogLevel( DefaultLogLevel.getLogLevel() );
		
	    // Fill the parameters, maybe do this in another place?
		Map<String, String> params = executionConfiguration.getParams();
		params.clear();
		String[] paramNames = jobMeta.listParameters();
		for (String name : paramNames) {
			params.put(name, "");
		}
		
		JsonUtils.response(JobExecutionConfigurationCodec.encode(executionConfiguration));
	}
	
	/**
	 * 执行作业
	 * 
	 * @param graphXml
	 * @param executionConfiguration
	 * @throws Exception
	 */
	@ResponseBody
	@RequestMapping(method=RequestMethod.POST, value="/run")
	protected void run(@RequestParam String graphXml, @RequestParam String executionConfiguration) throws Exception {
		GraphCodec codec = (GraphCodec) PluginFactory.getBean(GraphCodec.JOB_CODEC);
		JobMeta jobMeta = (JobMeta) codec.decode(graphXml);
		
		JSONObject jsonObject = JSONObject.fromObject(executionConfiguration);
		JobExecutionConfiguration jobExecutionConfiguration = JobExecutionConfigurationCodec.decode(jsonObject, jobMeta);
		
	    JobExecutor jobExecutor = JobExecutor.initExecutor(jobExecutionConfiguration, jobMeta);
	    Thread tr = new Thread(jobExecutor, "JobExecutor_" + jobExecutor.getExecutionId());
	    tr.start();
        executions.put(jobExecutor.getExecutionId(), jobExecutor);
		
        JsonUtils.success(jobExecutor.getExecutionId());
	}
	
	/**
	 * 获取执行结果
	 * 
	 * @param executionId
	 * @throws Exception
	 */
	@ResponseBody
	@RequestMapping(method=RequestMethod.POST, value="/result")
	protected void result(@RequestParam String executionId) throws Exception {
		JSONObject jsonObject = new JSONObject();
		
		JobExecutor jobExecutor = executions.get(executionId);
		System.out.println("jobExecutor_" + executionId + ": " + jobExecutor.isFinished());
		jsonObject.put("finished", jobExecutor.isFinished());
		if(jobExecutor.isFinished()) {
			executions.remove(executionId);
			
			jsonObject.put("jobMeasure", jobExecutor.getJobMeasure());
			jsonObject.put("log", StringEscapeHelper.encode(jobExecutor.getExecutionLog()));
//			jsonObject.put("stepStatus", transExecutor.getStepStatus());
//			jsonObject.put("previewData", transExecutor.getPreviewData());
		} else {
			jsonObject.put("jobMeasure", jobExecutor.getJobMeasure());
			jsonObject.put("log", StringEscapeHelper.encode(jobExecutor.getExecutionLog()));
//			jsonObject.put("stepStatus", transExecutor.getStepStatus());
//			jsonObject.put("previewData", transExecutor.getPreviewData());
		}
		
		JsonUtils.response(jsonObject);
	}
	
	private static HashMap<String, JobExecutor> executions = new HashMap<String, JobExecutor>();
}
