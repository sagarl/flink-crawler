package com.scaleunlimited.flinkcrawler.sources;

import java.util.LinkedList;

import org.apache.flink.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.scaleunlimited.flinkcrawler.pojos.RawUrl;

/**
 * Source for seed URLs
 * 
 * TODO add checkpointing - see FromElementsFunction.java
 *
 */
@SuppressWarnings("serial")
public class SeedUrlSource extends BaseUrlSource {
	static final Logger LOGGER = LoggerFactory.getLogger(SeedUrlSource.class);

	private RawUrl[] _unpartitioned;
	
	private volatile boolean _keepRunning = false;
	private transient LinkedList<RawUrl> _urls;
	
	public SeedUrlSource(float estimatedScore, String... rawUrls) throws Exception {
		_unpartitioned = new RawUrl[rawUrls.length];
		
		for (int i = 0; i < rawUrls.length; i++) {
			String url = rawUrls[i];
			_unpartitioned[i] = new RawUrl(url, estimatedScore);
		}
	}

	public SeedUrlSource(RawUrl... rawUrls) {
		_unpartitioned = rawUrls;
	}

	@Override
	public void open(Configuration parameters) throws Exception {
		super.open(parameters);
		
		_urls = new LinkedList<>();
		
		// Figure out which URLs in the full (unpartitioned) set are ones that
		// this source task should be emitting.
		for (int i = 0; i < _unpartitioned.length; i++) {
			if ((i % _parallelism) == _index) {
				_urls.add(_unpartitioned[i]);
			}
		}
	}
	
	@Override
	public void cancel() {
		_keepRunning = false;
	}

	@Override
	public void run(SourceContext<RawUrl> context) throws Exception {
		_keepRunning = true;
		
		while (_keepRunning && !_urls.isEmpty()) {
			RawUrl url = _urls.pop();
			LOGGER.debug(String.format("Emitting %s for %d of %d", url, _index, _parallelism));
			context.collect(url);
		}
	}

}
