package org.flhy.webapp.controller;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.flhy.ext.PluginFactory;
import org.flhy.ext.utils.SvgImageUrl;
import org.flhy.webapp.utils.StepImageManager;
import org.pentaho.di.laf.BasePropertyHandler;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping(value="/ui")
public class KettleUIController {

	@ResponseBody
	@RequestMapping(method={RequestMethod.POST, RequestMethod.GET}, value="/images/{name}")
	protected void images(HttpServletRequest request, HttpServletResponse response, @PathVariable String name) throws Exception {
		int scale = 16;
		String scale_str = request.getParameter("scale");
		if(StringUtils.hasText(scale_str) && scale_str.matches("\\d+"))
			scale = Integer.parseInt(scale_str);
		
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		BufferedImage image = StepImageManager.getUniversalImage(cl, "ui/images/" + name + ".svg", scale);
		response.setContentType("image/png");
		ImageIO.write(image, "PNG", response.getOutputStream());
		response.getOutputStream().flush();
	}
	
	@ResponseBody
	@RequestMapping(method={RequestMethod.POST, RequestMethod.GET}, value="/css/{name}")
	protected void css(HttpServletRequest request, HttpServletResponse response, @PathVariable String name) throws Exception {
		StringBuffer sb = new StringBuffer();
		for(Map.Entry<String, String> entry : images.entrySet()) {
			String url = request.getContextPath()+ "/" + entry.getValue();
			sb.append("." + entry.getKey() + " { background-image: url(" + url + ") !important;}\n\n");
		}
		
		response.setContentType("text/css");
		response.getWriter().write(sb.toString());
	}
	
	@ResponseBody
	@RequestMapping(method={RequestMethod.POST, RequestMethod.GET}, value="/stepjs/{pluginId}")
	protected void stepjs(HttpServletRequest request, HttpServletResponse response, @PathVariable String pluginId) throws Exception {
		if(PluginFactory.containBean(pluginId)) {
			Object plugin = PluginFactory.getBean(pluginId);
			ClassPathResource cpr = new ClassPathResource(pluginId + ".js", plugin.getClass());
			InputStream input = null;
			try {
				input = cpr.getInputStream();
				response.setContentType("text/javascript;charset=utf-8");
				IOUtils.copy(input, response.getOutputStream());
			} finally {
				IOUtils.closeQuietly(input);
			}
		}
	}
	
	static HashMap<String, String> images = new HashMap<String, String>();
	
	static {
		// 执行结果面板上的图标
		images.put("imageShowHistory", SvgImageUrl.getSmallUrl(BasePropertyHandler.getProperty( "ShowHistory_image" )));
		images.put("imageShowLog", SvgImageUrl.getSmallUrl(BasePropertyHandler.getProperty( "ShowLog_image" )));
		images.put("imageShowGrid", SvgImageUrl.getSmallUrl(BasePropertyHandler.getProperty( "ShowGrid_image" )));
		images.put("imageShowPerf", SvgImageUrl.getSmallUrl(BasePropertyHandler.getProperty( "ShowPerf_image" )));
		images.put("imageGantt", SvgImageUrl.getSmallUrl(BasePropertyHandler.getProperty( "Gantt_image" )));
		images.put("imagePreview", SvgImageUrl.getSmallUrl(BasePropertyHandler.getProperty( "Preview_image" )));
		
		// 转换和作业的小图标
		images.put("trans", SvgImageUrl.getSmallUrl(BasePropertyHandler.getProperty( "SpoonIcon_image" )));
		images.put("job", SvgImageUrl.getSmallUrl(BasePropertyHandler.getProperty( "ChefIcon_image" )));
		
		// 工具栏上的图标
		images.put("save", SvgImageUrl.getSmallUrl("ui/images/save.svg"));
		images.put("run", SvgImageUrl.getSmallUrl("ui/images/run.svg"));
		images.put("pause", SvgImageUrl.getSmallUrl("ui/images/pause.svg"));
		images.put("stop", SvgImageUrl.getSmallUrl("ui/images/stop.svg"));
		images.put("preview", SvgImageUrl.getSmallUrl("ui/images/preview.svg"));
		images.put("debug", SvgImageUrl.getSmallUrl("ui/images/debug.svg"));
		
		images.put("replay", SvgImageUrl.getSmallUrl("ui/images/replay.svg"));
		images.put("check", SvgImageUrl.getSmallUrl("ui/images/check.svg"));
		images.put("impact", SvgImageUrl.getSmallUrl("ui/images/impact.svg"));
		images.put("SQLbutton", SvgImageUrl.getSmallUrl("ui/images/SQLbutton.svg"));
		images.put("exploredb", SvgImageUrl.getSmallUrl("ui/images/exploredb.svg"));
		
		images.put("SlaveServer", SvgImageUrl.getSmallUrl(BasePropertyHandler.getProperty( "Slave_image" )));
		images.put("ClusterSchema", SvgImageUrl.getSmallUrl(BasePropertyHandler.getProperty( "Cluster_image" )));
		
		images.put("show-results", SvgImageUrl.getSmallUrl("ui/images/show-results.svg"));
		
		// javscript组件图标
		images.put("underGreen", SvgImageUrl.getSmallUrl( "ui/images/underGreen.svg"));
		images.put("arrowGreen", SvgImageUrl.getSmallUrl( "ui/images/arrowGreen.svg"));
		images.put("arrowOrange", SvgImageUrl.getSmallUrl( "ui/images/arrowOrange.svg"));
		
		images.put("addNew", SvgImageUrl.getSmallUrl( "ui/images/addSmall.svg"));
		images.put("addCopy", SvgImageUrl.getSmallUrl( "ui/images/copySmall.svg"));
		images.put("activeScript", SvgImageUrl.getSmallUrl( "ui/images/faScript.svg"));
		images.put("activeStartScript", SvgImageUrl.getSmallUrl( "ui/images/SQLbutton.svg"));
		images.put("activeEndScript", SvgImageUrl.getSmallUrl( "ui/images/edfScript.svg"));
		images.put("scriptType", SvgImageUrl.getSmallUrl( "ui/images/hide-inactive.svg"));
	}
}
