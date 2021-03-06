package com.voya.core.cache.manager;


import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Logger;

import javax.annotation.Resource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.data.gemfire.GemfireSystemException;
import org.springframework.data.gemfire.support.GemfireCache;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.client.ClientCache;
import com.gemstone.gemfire.cache.client.ClientRegionShortcut;
import com.gemstone.gemfire.cache.client.Pool;
import com.gemstone.gemfire.cache.execute.Execution;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.pdx.JSONFormatter;
import com.gemstone.gemfire.pdx.JSONFormatterException;
import com.gemstone.gemfire.pdx.PdxInstance;
import com.voya.core.functions.CreateRegionFunction;


@Component
public class RegionCreator {

	protected final Logger log = Logger.getLogger(getClass().getName());
	final static Charset ENCODING = StandardCharsets.UTF_8;

	@Autowired
	private Pool pool;

	@Autowired
	@Resource(name="gemfireClientCache")
	private ClientCache voyaCache;
	
	@Value("${voya.cache.specs.directory}")
	private String cacheSpecsDir;

	@Autowired
	private RegionCreationStrategy regionCreationStrategy;

	void init() {
	    getRegionCreationStrategy();
	    log.info(String.format("Using... (%1$s)", ObjectUtils.nullSafeClassName(getRegionCreationStrategy())));
	}
	
    Cache createRegion(String regionName) {
    	
    	Region<?, ?> region = null;
    	PdxInstance regionOptions = readRegionOptions(regionName);

    	boolean remoteRegionCreated = getRegionCreationStrategy().createRegion(regionName, regionOptions, pool);
		if(remoteRegionCreated){
			region = voyaCache.createClientRegionFactory(ClientRegionShortcut.PROXY).create(regionName);
		} else {
			throw new GemfireSystemException(new RuntimeException("Could not create remote region"));
		}
		return new GemfireCache(region);    
    }

    public RegionCreationStrategy getRegionCreationStrategy() {
      Assert.state(regionCreationStrategy != null,
        "A reference to a RegionCreationStrategy was not properly configured and initialied!");
      return regionCreationStrategy;
    }

    public static interface RegionCreationStrategy {
      boolean createRegion(String regionNameToCreate, PdxInstance regionOptions, Pool pool);
    }

    public static class ServerSideRegionCreationStrategy implements RegionCreationStrategy {

      @Override
      public boolean createRegion(String regionNameToCreate, PdxInstance regionOptions, Pool pool) {
    	List<Object> args = new ArrayList<Object>(Arrays.asList(regionNameToCreate, regionOptions));
    	  
      	Execution fnExec = FunctionService.onServer(pool).withArgs(args);
      	
      	ResultCollector<?, ?> collector = fnExec.execute(CreateRegionFunction.FUNCTION_ID);
      	List<?> results = (List<?>) collector.getResult();
      	boolean wasRegionCreated = (Boolean) results.get(0);
      	Assert.isTrue(wasRegionCreated);
  		return wasRegionCreated;
      }
    }
    
    public PdxInstance readRegionOptions(String regionName) {

    	PdxInstance regionOptions = null;
    	Path pathToRegionOptions = Paths.get(cacheSpecsDir + "/" + regionName + ".json");
    	Scanner scanner = null;
    	try {
    		scanner = new Scanner(pathToRegionOptions);
    		String regionOptionsJson = scanner.useDelimiter("\\Z").next();
	    	regionOptions = validateRegionOptionsJson(regionOptionsJson);
    	}
		catch (JSONFormatterException ex) {
			log.info("Your JSON at: " + pathToRegionOptions.toAbsolutePath() + " is not correctly formatted. I cannot continue.");
    		throw new GemfireSystemException(new RuntimeException("Your JSON at: " + pathToRegionOptions.toAbsolutePath() + " is not correctly formatted. I cannot continue."));
		}
    	catch(NoSuchFileException ex) {
    		log.info("Could not find file " + pathToRegionOptions.toAbsolutePath());
    		throw new GemfireSystemException(new RuntimeException("Could not locate Region Options file " + pathToRegionOptions.toString()));
    	}
        catch(IOException ex) {
        	log.info(ex.toString());
			throw new GemfireSystemException(new RuntimeException("Error reading Region Options file " + pathToRegionOptions.toString()));
        }
    	finally {
    		if (scanner != null)
    			scanner.close();
    	}
        
        return regionOptions;
    }
    
    private PdxInstance validateRegionOptionsJson(String regionOptionsJson) throws JSONFormatterException {
    	PdxInstance regionOptions = null;
    	regionOptions = JSONFormatter.fromJSON(regionOptionsJson);
		return regionOptions;
    }
}
