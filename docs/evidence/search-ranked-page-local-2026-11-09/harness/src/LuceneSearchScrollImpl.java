/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.search.backend.lucene.search.query.impl;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import org.hibernate.search.backend.lucene.logging.impl.LuceneMiscLog;
import org.hibernate.search.backend.lucene.logging.impl.QueryLog;
import org.hibernate.search.backend.lucene.lowlevel.reader.impl.HibernateSearchMultiReader;
import org.hibernate.search.backend.lucene.orchestration.impl.LuceneSyncWorkOrchestrator;
import org.hibernate.search.backend.lucene.search.query.LuceneSearchResult;
import org.hibernate.search.backend.lucene.search.query.LuceneSearchScroll;
import org.hibernate.search.backend.lucene.search.query.LuceneSearchScrollResult;
import org.hibernate.search.backend.lucene.work.impl.LuceneSearcher;
import org.hibernate.search.backend.lucene.work.impl.LuceneWorkFactory;
import org.hibernate.search.backend.lucene.work.impl.ReadWork;
import org.hibernate.search.engine.reporting.spi.EventContexts;
import org.hibernate.search.engine.search.query.SearchResultTotal;
import org.hibernate.search.engine.search.query.spi.SimpleSearchResultTotal;
import org.hibernate.search.engine.search.timeout.spi.TimeoutManager;

import org.apache.lucene.search.ScoreDoc;

public class LuceneSearchScrollImpl<H> implements LuceneSearchScroll<H> {

	// shared with its query instance:
	private final LuceneSyncWorkOrchestrator queryOrchestrator;
	private final LuceneWorkFactory workFactory;
	private final LuceneSearchQueryIndexScope<?, ?> scope;
	private final Set<String> routingKeys;
	private final TimeoutManager timeoutManager;
	private final LuceneSearcher<LuceneLoadableSearchResult<H>, LuceneExtractableSearchResult<H>> searcher;
	private final int totalHitCountThreshold;

	// specific to this scroll instance:
	private final HibernateSearchMultiReader indexReader;
	private final int chunkSize;

	private final int currentPageLimit;
	private LuceneExtractableSearchResult<H> currentPage;
	private int nextChunkStartIndexInPage = 0;
	private long hitCountSoFar = 0;

	public LuceneSearchScrollImpl(LuceneSyncWorkOrchestrator queryOrchestrator,
			LuceneWorkFactory workFactory, LuceneSearchQueryIndexScope<?, ?> scope,
			Set<String> routingKeys,
			TimeoutManager timeoutManager,
			LuceneSearcher<LuceneLoadableSearchResult<H>, LuceneExtractableSearchResult<H>> searcher,
			int totalHitCountThreshold,
			HibernateSearchMultiReader indexReader, int chunkSize) {
		this.queryOrchestrator = queryOrchestrator;
		this.workFactory = workFactory;
		this.scope = scope;
		this.routingKeys = routingKeys;
		this.timeoutManager = timeoutManager;
		this.searcher = searcher;
		this.totalHitCountThreshold = totalHitCountThreshold;
		this.indexReader = indexReader;
		this.chunkSize = chunkSize;
		int originalPageLimit = (int) Math.min( (long) chunkSize * 4, Integer.MAX_VALUE );
		this.currentPageLimit = Integer.getInteger( "ranked.scroll.experimental-page-limit", originalPageLimit );
		if ( currentPageLimit < originalPageLimit || currentPageLimit % chunkSize != 0 ) {
			throw new IllegalArgumentException( "Experimental page limit must be a whole number of chunks" );
		}
	}

	@Override
	public void close() {
		try {
			indexReader.close();
		}
		catch (IOException | RuntimeException e) {
			LuceneMiscLog.INSTANCE.unableToCloseIndexReader(
					EventContexts.fromIndexNames( scope.hibernateSearchIndexNames() ),
					e );
		}
		finally {
			currentPage = null;
		}
	}

	@Override
	public LuceneSearchScrollResult<H> next() {
		timeoutManager.start();
		try {
			return doNext();
		}
		finally {
			timeoutManager.stop();
		}
	}

	private LuceneSearchScrollResult<H> doNext() {
		if ( currentPage == null || nextChunkStartIndexInPage >= currentPageLimit ) {
			ScoreDoc after = currentPage == null ? null : currentPage.lastScoreDoc();
			currentPage = doSubmitWithIndexReader(
					workFactory.scroll( searcher, after, currentPageLimit, totalHitCountThreshold ),
					indexReader );
			nextChunkStartIndexInPage = 0;
		}

		// no more results check
		if ( nextChunkStartIndexInPage >= currentPage.hitSize() ) {
			return new LuceneSearchScrollResultImpl<>( total(), false, Collections.emptyList(),
					timeoutManager.tookTime(), timeoutManager.isTimedOut() );
		}

		int nextChunkEndIndexInPage = (int) Math.min( (long) nextChunkStartIndexInPage + chunkSize, currentPage.hitSize() );

		LuceneLoadableSearchResult<H> loadableSearchResult;
		try {
			loadableSearchResult = currentPage.extract( nextChunkStartIndexInPage, nextChunkEndIndexInPage );
		}
		catch (IOException e) {
			throw QueryLog.INSTANCE.ioExceptionOnQueryExecution( searcher.getLuceneQueryForExceptions(), e.getMessage(),
					EventContexts.fromIndexNames( scope.hibernateSearchIndexNames() ), e );
		}

		/*
		 * WARNING: the following call must run in the user thread.
		 * If we introduce async processing, we will have to add a nextAsync method here,
		 * as well as in ProjectionHitMapper and EntityLoader.
		 * This method may not be easy to implement for blocking mappers,
		 * so we may choose to throw exceptions for those.
		 */
		LuceneSearchResult<H> result = loadableSearchResult.loadBlocking();

		// increasing the index for further next(s)
		hitCountSoFar += nextChunkEndIndexInPage - nextChunkStartIndexInPage;
		nextChunkStartIndexInPage = nextChunkEndIndexInPage;
		return new LuceneSearchScrollResultImpl<>( total(), true, result.hits(),
				result.took(), result.timedOut() );
	}

	private SearchResultTotal total() {
		SearchResultTotal total = currentPage.total();
		// A search-after collector may stop counting before reaching the number of hits already returned.
		return total.isHitCountLowerBound() && total.hitCountLowerBound() < hitCountSoFar
				? SimpleSearchResultTotal.lowerBound( hitCountSoFar )
				: total;
	}

	private <T> T doSubmitWithIndexReader(ReadWork<T> work, HibernateSearchMultiReader indexReader) {
		return queryOrchestrator.submit(
				scope.hibernateSearchIndexNames(),
				scope.indexes(),
				routingKeys,
				work, indexReader
		);
	}
}
