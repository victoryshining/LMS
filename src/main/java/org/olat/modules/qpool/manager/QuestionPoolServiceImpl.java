/**

 * <a href="http://www.openolat.org">
 * OpenOLAT - Online Learning and Training</a><br>
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); <br>
 * you may not use this file except in compliance with the License.<br>
 * You may obtain a copy of the License at the
 * <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache homepage</a>
 * <p>
 * Unless required by applicable law or agreed to in writing,<br>
 * software distributed under the License is distributed on an "AS IS" BASIS, <br>
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. <br>
 * See the License for the specific language governing permissions and <br>
 * limitations under the License.
 * <p>
 * Initial code contributed and copyrighted by<br>
 * frentix GmbH, http://www.frentix.com
 * <p>
 */
package org.olat.modules.qpool.manager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipOutputStream;

import org.olat.basesecurity.BaseSecurity;
import org.olat.basesecurity.IdentityRef;
import org.olat.basesecurity.SecurityGroup;
import org.olat.core.commons.persistence.DB;
import org.olat.core.commons.persistence.DefaultResultInfos;
import org.olat.core.commons.persistence.ResultInfos;
import org.olat.core.commons.persistence.SortKey;
import org.olat.core.commons.services.commentAndRating.CommentAndRatingService;
import org.olat.core.commons.services.mark.MarkManager;
import org.olat.core.gui.media.MediaResource;
import org.olat.core.id.Identity;
import org.olat.core.id.Roles;
import org.olat.core.logging.OLog;
import org.olat.core.logging.Tracing;
import org.olat.core.util.StringHelper;
import org.olat.core.util.vfs.LocalImpl;
import org.olat.core.util.vfs.VFSContainer;
import org.olat.core.util.vfs.VFSItem;
import org.olat.core.util.vfs.VFSLeaf;
import org.olat.group.BusinessGroup;
import org.olat.modules.qpool.ExportFormatOptions;
import org.olat.modules.qpool.ExportFormatOptions.Outcome;
import org.olat.modules.qpool.Pool;
import org.olat.modules.qpool.QPoolSPI;
import org.olat.modules.qpool.QPoolService;
import org.olat.modules.qpool.QuestionItem;
import org.olat.modules.qpool.QuestionItem2Pool;
import org.olat.modules.qpool.QuestionItem2Resource;
import org.olat.modules.qpool.QuestionItemCollection;
import org.olat.modules.qpool.QuestionItemFull;
import org.olat.modules.qpool.QuestionItemShort;
import org.olat.modules.qpool.QuestionItemView;
import org.olat.modules.qpool.QuestionPoolModule;
import org.olat.modules.qpool.QuestionStatus;
import org.olat.modules.qpool.ReviewService;
import org.olat.modules.qpool.model.DefaultExportFormat;
import org.olat.modules.qpool.model.PoolImpl;
import org.olat.modules.qpool.model.QEducationalContext;
import org.olat.modules.qpool.model.QItemDocument;
import org.olat.modules.qpool.model.QItemType;
import org.olat.modules.qpool.model.QLicense;
import org.olat.modules.qpool.model.QuestionItemImpl;
import org.olat.modules.qpool.model.ReviewDecision;
import org.olat.modules.qpool.model.SearchQuestionItemParams;
import org.olat.modules.taxonomy.Taxonomy;
import org.olat.modules.taxonomy.TaxonomyCompetenceTypes;
import org.olat.modules.taxonomy.TaxonomyLevel;
import org.olat.modules.taxonomy.TaxonomyLevelRef;
import org.olat.modules.taxonomy.TaxonomyRef;
import org.olat.modules.taxonomy.manager.TaxonomyCompetenceDAO;
import org.olat.modules.taxonomy.manager.TaxonomyDAO;
import org.olat.modules.taxonomy.manager.TaxonomyLevelDAO;
import org.olat.modules.taxonomy.model.TaxonomyRefImpl;
import org.olat.resource.OLATResource;
import org.olat.search.model.AbstractOlatDocument;
import org.olat.search.service.indexer.LifeFullIndexer;
import org.olat.search.service.searcher.SearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 
 * Initial date: 22.01.2013<br>
 * @author srosse, stephane.rosse@frentix.com, http://www.frentix.com
 *
 */
@Service("qpoolService")
public class QuestionPoolServiceImpl implements QPoolService {
	
	private static final OLog log = Tracing.createLoggerFor(QuestionPoolServiceImpl.class);
	
	private static final int MAX_NUMBER_DOCS = 990;
	
	@Autowired
	private DB dbInstance;
	@Autowired
	private PoolDAO poolDao;
	@Autowired
	private QItemQueriesDAO itemQueriesDao;
	@Autowired
	private CollectionDAO collectionDao;
	@Autowired
	private QLicenseDAO qpoolLicenseDao;
	@Autowired
	private QItemTypeDAO qpoolItemTypeDao;
	@Autowired
	private QEducationalContextDAO qEduContextDao;
	@Autowired
	private QuestionItemDAO questionItemDao;
	@Autowired
	private QPoolFileStorage qpoolFileStorage;
	@Autowired
	private QuestionPoolModule qpoolModule;
	@Autowired
	private BaseSecurity securityManager;
	@Autowired
	private SearchClient searchClient;
	@Autowired
	private LifeFullIndexer lifeIndexer;
	@Autowired
	private TaxonomyDAO taxonomyDao;
	@Autowired
	private TaxonomyLevelDAO taxonomyLevelDao;
	@Autowired
	private TaxonomyCompetenceDAO taxonomyCompetenceDao;
	@Autowired
	private CommentAndRatingService commentAndRatingService;
	@Autowired
	private MarkManager markManager;
	@Autowired
	private ReviewService reviewService;

	@Override
	public void deleteItems(List<? extends QuestionItemShort> items) {
		if(items == null || items.isEmpty()) {
			return; //nothing to do
		}
		
		List<SecurityGroup> secGroups = new ArrayList<>();
		for (QuestionItemShort item: items) {
			markManager.deleteMarks(item);
			commentAndRatingService.deleteAllIgnoringSubPath(item);
			QuestionItem loadedItem = loadItemById(item.getKey());
			if (loadedItem instanceof QuestionItemImpl) {
				QuestionItemImpl itemImpl = (QuestionItemImpl) loadedItem;
				qpoolFileStorage.deleteDir(itemImpl.getDirectory());
				secGroups.add(itemImpl.getOwnerGroup());
			}
			dbInstance.intermediateCommit();
		}
		
		poolDao.removeFromPools(items);
		questionItemDao.removeFromShares(items);
		collectionDao.deleteItemFromCollections(items);
		questionItemDao.delete(items);
		
		// Delete SecurityGroup after the item to avoid foreign key constraint violation.
		for (SecurityGroup secGroup: secGroups) {
			securityManager.deleteSecurityGroup(secGroup);
		}
		
		for(QuestionItemShort item:items) {
			lifeIndexer.deleteDocument(QItemDocument.TYPE, item.getKey());
		}
		
		dbInstance.getCurrentEntityManager().flush();//allow reload of data
	}

	@Override
	public void addAuthors(List<Identity> authors, List<QuestionItemShort> items) {
		if(authors == null || authors.isEmpty() || items == null || items.isEmpty()) {
			return;//nothing to do
		}
		
		for(QuestionItemShort item:items) {
			questionItemDao.addAuthors(authors, item);
		}
	}
	
	@Override
	public void removeAuthors(List<Identity> authors, List<QuestionItemShort> items) {
		if(authors == null || authors.isEmpty() || items == null || items.isEmpty()) {
			return;//nothing to do
		}
		
		for(QuestionItemShort item:items) {
			questionItemDao.removeAuthors(authors, item);
		}
	}

	@Override
	public List<Identity> getAuthors(QuestionItem item) {
		QuestionItemImpl itemImpl;
		if(item instanceof QuestionItemImpl) {
			itemImpl = (QuestionItemImpl)item;
		} else {
			itemImpl = questionItemDao.loadById(item.getKey());
		}
		return securityManager.getIdentitiesOfSecurityGroup(itemImpl.getOwnerGroup());
	}
	
	@Override
	public QuestionItem loadItemById(Long key) {
		return questionItemDao.loadById(key);
	}
	
	@Override
	public List<QuestionItem> loadItemByIdentifier(String identifier) {
		return questionItemDao.loadByIdentifier(identifier);
	}

	@Override
	public QuestionItem updateItem(QuestionItem item) {
		QuestionItem previousItem = loadItemById(item.getKey());
		QuestionStatus previousStatus = previousItem != null? previousItem.getQuestionStatus(): null;
		QuestionStatus newStatus = item.getQuestionStatus();
		if (statusChanged(previousStatus, newStatus)) {
			if (item instanceof QuestionItemImpl) {
				QuestionItemImpl itemImpl = (QuestionItemImpl) item;
				itemImpl.setQuestionStatusLastModified(new Date());
			}
			if (reviewService.isReviewStarting(previousStatus, newStatus)) {
				reviewService.startReview(item);
			}
		}
		QuestionItem mergedItem = questionItemDao.merge(item);
		dbInstance.commit();
		lifeIndexer.indexDocument(QItemDocument.TYPE, mergedItem.getKey());
		return mergedItem;
	}
	
	private boolean statusChanged(QuestionStatus previousStatus, QuestionStatus newStatus) {
		return previousStatus != null && !previousStatus.equals(newStatus);
	}

	@Override
	public void index(List<? extends QuestionItemShort> items) {
		if(items == null || items.isEmpty()) return;
		
		List<Long> keys = new ArrayList<>();
		for(QuestionItemShort item:items) {
			keys.add(item.getKey());
		}
		lifeIndexer.indexDocument(QItemDocument.TYPE, keys);
	}

	@Override
	public List<QuestionItem> copyItems(Identity owner, List<QuestionItemShort> itemsToCopy) {
		List<QuestionItem> copies = new ArrayList<>();
		for(QuestionItemShort itemToCopy:itemsToCopy) {
			QuestionItemImpl original = questionItemDao.loadById(itemToCopy.getKey());
			QuestionItemImpl copy = questionItemDao.copy(original);
			questionItemDao.persist(owner, copy);
			QPoolSPI provider = qpoolModule.getQuestionPoolProvider(copy.getFormat());
			if(provider != null) {
				provider.copyItem(original, copy);
			}
			copies.add(copy);
		}
		if(copies.size()> 0) {// reload of data must be possible in the same transaction
			dbInstance.getCurrentEntityManager().flush();
		}
		return copies;
	}

	@Override
	public List<QuestionItem> importItems(Identity owner, Locale defaultLocale,  String filename, File file) {
		List<QuestionItem> importedItem = null;
		List<QPoolSPI> providers = qpoolModule.getQuestionPoolProviders();
		for(QPoolSPI provider:providers) {
			if(provider.isCompatible(filename, file)) {
				importedItem = provider.importItems(owner, defaultLocale, filename, file);
			}	
		}
		if(importedItem != null && importedItem.size() > 0) {
			dbInstance.getCurrentEntityManager().flush();
		}
		return importedItem;
	}
	
	@Override
	public MediaResource export(List<QuestionItemShort> items, ExportFormatOptions format, Locale locale) {
		MediaResource mr = null;
		if(DefaultExportFormat.ZIP_EXPORT_FORMAT.equals(format)) {
			List<Long> keys = toKeys(items);
			List<QuestionItemFull> fullItems = questionItemDao.loadByIds(keys);
			mr = new ExportQItemsZipResource("UTF-8", locale, fullItems);
			//make a zip with all items
		} else {
			QPoolSPI selectedSp = null;
			List<QPoolSPI> sps = qpoolModule.getQuestionPoolProviders();
			for(QPoolSPI sp:sps) {
				if(sp.getTestExportFormats().contains(format)) {
					selectedSp = sp;
					break;
				}	
			}
			
			if(selectedSp != null) {
				mr = selectedSp.exportTest(items, format, locale);
			}
		}
		return mr;
	}
	
	private List<Long> toKeys(List<? extends QuestionItemShort> items) {
		if(items == null || items.isEmpty()) return Collections.emptyList();
		List<Long> keys = new ArrayList<Long>(items.size());
		for(QuestionItemShort item:items) {
			keys.add(item.getKey());
		}
		return keys;
	}

	@Override
	public void exportItem(QuestionItemShort item, ZipOutputStream zout, Locale locale, Set<String> names) {
		QPoolSPI provider = qpoolModule.getQuestionPoolProvider(item.getFormat());
		if(provider == null) {
			log.error("Not found provider for this format: " + item.getFormat());
		} else {
			QuestionItemFull fullItem;
			if(item instanceof QuestionItemFull) {
				fullItem = (QuestionItemFull)item;
			} else {
				fullItem = questionItemDao.loadById(item.getKey());
			}
			provider.exportItem(fullItem, zout, locale, names);
		}
	}

	@Override
	public Set<ExportFormatOptions> getExportFormatOptions(List<QuestionItemShort> items, Outcome outcome) {
		return items.stream()
				.map(QuestionItemShort::getFormat)
				.map(qpoolModule::getQuestionPoolProvider)
				.map(QPoolSPI::getTestExportFormats)
				.flatMap(List::stream)
				.filter(exportFormat -> exportFormat.getOutcome().equals(outcome))
				.collect(Collectors.toSet()); 
	}

	@Override
	public File getRootFile(QuestionItem item) {
		VFSLeaf leaf = getRootLeaf(item);
		return leaf == null ? null : ((LocalImpl)leaf).getBasefile();
	}

	@Override
	public File getRootDirectory(QuestionItem item) {
		VFSContainer container = getRootContainer(item);
		return container == null ? null : ((LocalImpl)container).getBasefile();
	}

	@Override
	public VFSLeaf getRootLeaf(QuestionItemShort item) {
		QuestionItemImpl reloadedItem = questionItemDao.loadById(item.getKey());
		if(reloadedItem == null) {
			return null;
		}
		
		VFSContainer root = qpoolModule.getRootContainer();
		VFSItem dir = root.resolve(reloadedItem.getDirectory());
		if(dir instanceof VFSContainer) {
			VFSContainer itemContainer = (VFSContainer)dir;
			VFSItem rootLeaf = itemContainer.resolve(reloadedItem.getRootFilename());
			if(rootLeaf instanceof VFSLeaf) {
				return (VFSLeaf)rootLeaf;
			}
		}
		return null;
	}

	@Override
	public VFSContainer getRootContainer(QuestionItemShort item) {
		QuestionItemImpl reloadedItem = questionItemDao.loadById(item.getKey());
		VFSContainer root = qpoolModule.getRootContainer();
		VFSItem dir = root.resolve(reloadedItem.getDirectory());
		if(dir instanceof VFSContainer) {
			return (VFSContainer)dir;
		}
		return null;
	}

	@Override
	public QuestionItem createAndPersistItem(Identity owner, String subject, String format, String language,
			TaxonomyLevel taxonLevel, String dir, String rootFilename, QItemType type) {
		QuestionItemImpl newItem = questionItemDao.createAndPersist(owner, subject, format, language, taxonLevel, dir, rootFilename, type);
		dbInstance.commit();
		lifeIndexer.indexDocument(QItemDocument.TYPE, newItem.getKey());
		return newItem;
	}

	@Override
	public List<QuestionItemFull> getAllItems(int firstResult, int maxResults) {
		return questionItemDao.getAllItems(firstResult, maxResults);
	}

	@Override
	public List<Pool> getPools(Identity identity, Roles roles) {
		if(roles.isOLATAdmin()) {
			return poolDao.getPools(0, -1);
		}
		return poolDao.getPools(identity, 0, -1);
	}
	
	@Override
	public boolean isMemberOfPrivatePools(IdentityRef identity) {
		return poolDao.isMemberOfPrivatePools(identity);
	}

	@Override
	public List<QuestionItem2Pool> getPoolInfosByItem(QuestionItemShort item) {
		return poolDao.getQuestionItem2Pool(item);
	}

	@Override
	public boolean isOwner(Identity owner, Pool pool) {
		if(pool == null || owner == null) return false;
		
		SecurityGroup secGroup = ((PoolImpl)pool).getOwnerGroup();
		return securityManager.isIdentityInSecurityGroup(owner, secGroup);
	}

	@Override
	public void addOwners(List<Identity> owners, List<Pool> pools) {
		if(owners == null || owners.isEmpty() || pools == null || pools.isEmpty()) {
			return;//nothing to do
		}
		
		for(Pool pool:pools) {
			SecurityGroup secGroup = ((PoolImpl)pool).getOwnerGroup();
			for(Identity owner:owners) {
				if(!securityManager.isIdentityInSecurityGroup(owner, secGroup)) {
					securityManager.addIdentityToSecurityGroup(owner, secGroup);
				}
			}
		}
	}

	@Override
	public void removeOwners(List<Identity> owners, List<Pool> pools) {
		if(owners == null || owners.isEmpty() || pools == null || pools.isEmpty()) {
			return;//nothing to do
		}

		List<SecurityGroup> secGroups = new ArrayList<SecurityGroup>(pools.size());
		for(Pool pool:pools) {
			SecurityGroup secGroup = ((PoolImpl)pool).getOwnerGroup();
			secGroups.add(secGroup);
		}
		securityManager.removeIdentityFromSecurityGroups(owners, secGroups);
	}

	@Override
	public void addItemsInPools(List<? extends QuestionItemShort> items, List<Pool> pools, boolean editable) {
		if(items == null || items.isEmpty() || pools == null || pools.isEmpty()) {
			return;//nothing to do
		}
		
		List<Long> keys = new ArrayList<>(items.size());
		for(QuestionItemShort item:items) {
			poolDao.addItemToPool(item, pools, editable);
			keys.add(item.getKey());
		}
		dbInstance.commit();
		lifeIndexer.indexDocument(QItemDocument.TYPE, keys);
	}

	@Override
	public void removeItemsInPool(List<QuestionItemShort> items, Pool pool) {
		poolDao.removeFromPool(items, pool);
		
		List<Long> keys = new ArrayList<>(items.size());
		for(QuestionItemShort item:items) {
			keys.add(item.getKey());
		}
		lifeIndexer.indexDocument(QItemDocument.TYPE, keys);
	}

	@Override
	public List<QuestionItemShort> getItems(TaxonomyLevelRef level) {
		return questionItemDao.getItems(level);
	}

	@Override
	public int countItems(SearchQuestionItemParams searchParams) {
		if(searchParams.isFavoritOnly()) {
			return itemQueriesDao.countFavoritItems(searchParams);
		} else if(searchParams.getPoolKey() != null) {
			return poolDao.countItemsInPool(searchParams);
		} else if(searchParams.getAuthor() != null) {
			return itemQueriesDao.countItemsByAuthor(searchParams);
		} else {
			return itemQueriesDao.countItems(searchParams);
		}
	}

	@Override
	public ResultInfos<QuestionItemView> getItems(SearchQuestionItemParams searchParams,
			int firstResult, int maxResults, SortKey... orderBy) {
		if(searchParams.isFavoritOnly()) {
			return searchFavorits(searchParams, firstResult, maxResults, orderBy);
		} else if(searchParams.getAuthor() != null) {
			return searchByAuthor(searchParams, firstResult, maxResults, orderBy);
		} else if(searchParams.getPoolKey() != null) {
			return getItemsByPool(searchParams, firstResult, maxResults, orderBy);
		} else {
			return getItemsByParams(searchParams, firstResult, maxResults, orderBy);
		}
	}

	private ResultInfos<QuestionItemView> getItemsByParams(SearchQuestionItemParams searchParams, int firstResult,
			int maxResults, SortKey... orderBy) {
		ResultInfos<QuestionItemView> resultInfo = new DefaultResultInfos<>();
		if (searchParams.isFulltextSearch()) {
			try {
				String queryString = searchParams.getSearchString();
				List<String> condQueries = new ArrayList<>();
				if (searchParams.getCondQueries() != null) {
					condQueries.addAll(searchParams.getCondQueries());
				}
				addConditionsOfParams(condQueries, searchParams);
				List<Long> results = searchClient.doSearch(queryString, condQueries, searchParams.getIdentity(),
						searchParams.getRoles(), 0, MAX_NUMBER_DOCS);
				if (!results.isEmpty()) {
					List<QuestionItemView> items = itemQueriesDao.getItems(searchParams, results, firstResult,
							maxResults, orderBy);
					resultInfo = new DefaultResultInfos<>(firstResult + items.size(), results.size(), items);
				}
			} catch (Exception e) {
				log.error("", e);
			}
		} else {
			List<QuestionItemView> items = itemQueriesDao.getItems(searchParams, searchParams.getItemKeys(),
					firstResult, maxResults, orderBy);
			resultInfo = new DefaultResultInfos<>(firstResult + items.size(), -1, items);
		}
		return resultInfo;
	}

	private void addConditionsOfParams(List<String> condQueries, SearchQuestionItemParams searchParams) {
		if (searchParams.getQuestionStatus() != null) {
			condQueries.add(QItemDocument.ITEM_STATUS_FIELD + ":" + searchParams.getQuestionStatus());
		}
	}

	private ResultInfos<QuestionItemView> getItemsByPool(SearchQuestionItemParams searchParams, int firstResult, int maxResults, SortKey... orderBy) {
		if(searchParams.isFulltextSearch()) {
			try {
				String queryString = searchParams.getSearchString();
				List<String> condQueries = new ArrayList<String>();
				if(searchParams.getCondQueries() != null) {
					condQueries.addAll(searchParams.getCondQueries());
				}
				condQueries.add("pool:" + searchParams.getPoolKey());
				List<Long> results = searchClient.doSearch(queryString, condQueries,
						searchParams.getIdentity(), searchParams.getRoles(), 0, MAX_NUMBER_DOCS);

				if(results.isEmpty()) {
					return new DefaultResultInfos<QuestionItemView>();
				}
				List<QuestionItemView> items = itemQueriesDao.getItemsOfPool(searchParams, results, firstResult, maxResults, orderBy);
				return new DefaultResultInfos<QuestionItemView>(firstResult + items.size(), results.size(), items);
			} catch (Exception e) {
				log.error("", e);
			}
			return new DefaultResultInfos<QuestionItemView>();
		} else {
			List<QuestionItemView> items = itemQueriesDao.getItemsOfPool(searchParams, searchParams.getItemKeys(), firstResult, maxResults, orderBy);
			return new DefaultResultInfos<QuestionItemView>(firstResult + items.size(), -1, items);
		}
	}

	private ResultInfos<QuestionItemView> searchByAuthor(SearchQuestionItemParams searchParams, int firstResult, int maxResults, SortKey... orderBy) {
		Identity author = searchParams.getAuthor();
		if(searchParams.isFulltextSearch()) {
			try {
				String queryString = searchParams.getSearchString();
				List<String> condQueries = new ArrayList<String>();
				if(searchParams.getCondQueries() != null) {
					condQueries.addAll(searchParams.getCondQueries());
				}
				condQueries.add(QItemDocument.OWNER_FIELD + ":" + author.getKey());
				List<Long> results = searchClient.doSearch(queryString, condQueries,
						searchParams.getIdentity(), searchParams.getRoles(), 0, MAX_NUMBER_DOCS);

				if(results.isEmpty()) {
					return new DefaultResultInfos<QuestionItemView>();
				}
				List<QuestionItemView> items = itemQueriesDao.getItemsByAuthor(searchParams, results, firstResult, maxResults, orderBy);
				return new DefaultResultInfos<QuestionItemView>(firstResult + items.size(), results.size(), items);
			} catch (Exception e) {
				log.error("", e);
			}
			return new DefaultResultInfos<QuestionItemView>();
		} else {
			List<QuestionItemView> items = itemQueriesDao.getItemsByAuthor(searchParams, searchParams.getItemKeys(), firstResult, maxResults, orderBy);
			return new DefaultResultInfos<QuestionItemView>(firstResult + items.size(), -1, items);
		}
	}
	
	
	/**
	 * Do limit the search to favorit with the optimized view
	 * @param searchParams
	 * @param firstResult
	 * @param maxResults
	 * @param orderBy
	 * @return
	 */
	private ResultInfos<QuestionItemView> searchFavorits(SearchQuestionItemParams searchParams,
			int firstResult, int maxResults, SortKey... orderBy) {
		if(searchParams.isFulltextSearch()) {
			try {
				//filter with all favorits
				List<Long> favoritKeys = questionItemDao.getFavoritKeys(searchParams.getIdentity());

				String queryString = searchParams.getSearchString();
				List<String> condQueries = new ArrayList<String>();
				if(searchParams.getCondQueries() != null) {
					condQueries.addAll(searchParams.getCondQueries());
				}
				condQueries.add(getDbKeyConditionalQuery(favoritKeys));
				List<Long> results = searchClient.doSearch(queryString, condQueries,
						searchParams.getIdentity(), searchParams.getRoles(), 0, MAX_NUMBER_DOCS);

				if(results.isEmpty()) {
					return new DefaultResultInfos<QuestionItemView>();
				}
				List<QuestionItemView> items = itemQueriesDao.getFavoritItems(searchParams, results, firstResult, maxResults, orderBy);
				return new DefaultResultInfos<QuestionItemView>(firstResult + items.size(), results.size(), items);
			} catch (Exception e) {
				log.error("", e);
			}
			return new DefaultResultInfos<QuestionItemView>();
		} else {
			List<QuestionItemView> items = itemQueriesDao.getFavoritItems(searchParams, searchParams.getItemKeys(), firstResult, maxResults, orderBy);
			return new DefaultResultInfos<QuestionItemView>(firstResult + items.size(), -1, items);
		}
	}
	
	private String getDbKeyConditionalQuery(List<Long> keys) {
		StringBuilder sb = new StringBuilder();
		sb.append(AbstractOlatDocument.DB_ID_NAME).append(":(");
		for(Long key:keys) {
			if(sb.length() > 9) sb.append(" ");
			sb.append(key);
		}
		return sb.append(')').toString();
	}
	
	@Override
	public QuestionItemView getItem(Long key, Identity identity, Long restrictToPoolKey, Long restrictToGroupKey) {
		return itemQueriesDao.getItem(key, identity, restrictToPoolKey, restrictToGroupKey);
	}

	@Override
	public void shareItemsWithGroups(List<? extends QuestionItemShort> items, List<BusinessGroup> groups, boolean editable) {
		if(items == null || items.isEmpty() || groups == null || groups.isEmpty()) {
			return;//nothing to do
		}
		
		List<OLATResource> resources = new ArrayList<OLATResource>(groups.size());
		for(BusinessGroup group:groups) {
			resources.add(group.getResource());
		}
		
		for(QuestionItemShort item:items) {
			questionItemDao.share(item, resources, editable);
		}
		index(items);
	}

	@Override
	public void removeItemsFromResource(List<QuestionItemShort> items, OLATResource resource) {
		questionItemDao.removeFromShare(items, resource);
	}

	@Override
	public List<BusinessGroup> getResourcesWithSharedItems(Identity identity) {
		return questionItemDao.getResourcesWithSharedItems(identity);
	}

	@Override
	public int countSharedItemByResource(OLATResource resource, SearchQuestionItemParams searchParams) {
		return questionItemDao.countSharedItemByResource(resource, searchParams.getFormat());
	}

	@Override
	public ResultInfos<QuestionItemView> getSharedItemByResource(OLATResource resource, SearchQuestionItemParams searchParams,
			int firstResult, int maxResults, SortKey... orderBy) {
		
		if(searchParams != null && searchParams.isFulltextSearch()) {
			try {
				String queryString = searchParams.getSearchString();
				List<String> condQueries = new ArrayList<String>();
				if(searchParams.getCondQueries() != null) {
					condQueries.addAll(searchParams.getCondQueries());
				}
				condQueries.add(QItemDocument.SHARE_FIELD + ":" + resource.getKey());
				List<Long> results = searchClient.doSearch(queryString, condQueries,
						searchParams.getIdentity(), searchParams.getRoles(), 0, MAX_NUMBER_DOCS);
				if(results.isEmpty()) {
					return new DefaultResultInfos<QuestionItemView>();
				}
				List<QuestionItemView> items = itemQueriesDao.getSharedItemByResource(searchParams.getIdentity(), resource, results,
						searchParams.getFormat(), firstResult, maxResults);
				return new DefaultResultInfos<QuestionItemView>(firstResult + items.size(), results.size(), items);
			} catch (Exception e) {
				log.error("", e);
			}
			return new DefaultResultInfos<QuestionItemView>();
		} else {
			List<QuestionItemView> items = itemQueriesDao.getSharedItemByResource(searchParams.getIdentity(), resource, null,
					searchParams.getFormat(), firstResult, maxResults, orderBy);
			return new DefaultResultInfos<QuestionItemView>(firstResult + items.size(), -1, items);
		}
	}

	@Override
	public List<QuestionItem2Resource> getSharedResourceInfosByItem(QuestionItem item) {
		return questionItemDao.getSharedResourceInfos(item);
	}

	@Override
	public QuestionItemCollection createCollection(Identity owner, String collectionName, List<QuestionItemShort> initialItems) {
		QuestionItemCollection coll = collectionDao.createCollection(collectionName, owner);
		List<Long> keys = new ArrayList<>(initialItems.size());
		for(QuestionItemShort item:initialItems) {
			collectionDao.addItemToCollection(item, Collections.singletonList(coll));
			keys.add(item.getKey());
		}
		lifeIndexer.indexDocument(QItemDocument.TYPE, keys);
		return coll;
	}

	@Override
	public QuestionItemCollection renameCollection(QuestionItemCollection coll,	String name) {
		return collectionDao.mergeCollection(coll, name);
	}

	@Override
	public void deleteCollection(QuestionItemCollection coll) {
		collectionDao.deleteCollection(coll);
	}

	@Override
	public void addItemToCollection(List<? extends QuestionItemShort> items, List<QuestionItemCollection> collections) {
		List<Long> keys = new ArrayList<>(items.size());
		for(QuestionItemShort item:items) {
			collectionDao.addItemToCollection(item, collections);
			keys.add(item.getKey());
		}
		lifeIndexer.indexDocument(QItemDocument.TYPE, keys);
	}

	@Override
	public void removeItemsFromCollection(List<QuestionItemShort> items, QuestionItemCollection collection) {
		collectionDao.removeItemFromCollection(items, collection);
	}

	@Override
	public List<QuestionItemCollection> getCollections(Identity owner) {
		return collectionDao.getCollections(owner);
	}

	@Override
	public int countItemsOfCollection(QuestionItemCollection collection, SearchQuestionItemParams searchParams) {
		return collectionDao.countItemsOfCollection(collection, searchParams.getFormat());
	}

	@Override
	public ResultInfos<QuestionItemView> getItemsOfCollection(QuestionItemCollection collection, SearchQuestionItemParams searchParams,
			int firstResult, int maxResults, SortKey... orderBy) {
		
		if(searchParams != null && searchParams.isFulltextSearch()) {
			try {
				List<Long> content = collectionDao.getItemKeysOfCollection(collection);
				String queryString = searchParams.getSearchString();
				List<String> condQueries = new ArrayList<String>();
				if(searchParams.getCondQueries() != null) {
					condQueries.addAll(searchParams.getCondQueries());
				}
				condQueries.add(getDbKeyConditionalQuery(content));
				List<Long> results = searchClient.doSearch(queryString, condQueries,
						searchParams.getIdentity(), searchParams.getRoles(), 0, MAX_NUMBER_DOCS);

				if(results.isEmpty()) {
					return new DefaultResultInfos<QuestionItemView>();
				}
				List<QuestionItemView> items = itemQueriesDao.getItemsOfCollection(searchParams.getIdentity(), collection, results, 
						searchParams.getFormat(), firstResult, maxResults, orderBy);
				return new DefaultResultInfos<QuestionItemView>(firstResult + items.size(), results.size(), items);
			} catch (Exception e) {
				log.error("", e);
			}
			return new DefaultResultInfos<QuestionItemView>();
		} else {
			List<QuestionItemView> items = itemQueriesDao.getItemsOfCollection(searchParams.getIdentity(), collection, searchParams.getItemKeys(),
					searchParams.getFormat(), firstResult, maxResults, orderBy);
			return new DefaultResultInfos<QuestionItemView>(firstResult + items.size(), -1, items);
		}
	}

	@Override
	public void createPool(Identity identity, String name, boolean publicPool) {
		poolDao.createPool(identity, name, publicPool);
	}

	@Override
	public Pool updatePool(Pool pool) {
		return poolDao.updatePool(pool);
	}

	@Override
	public void deletePool(Pool pool) {
		poolDao.deletePool(pool);
	}

	@Override
	public int countPools() {
		return poolDao.countPools();
	}

	@Override
	public ResultInfos<Pool> getPools(int firstResult, int maxResults, SortKey... orderBy) {
		List<Pool> pools = poolDao.getPools(firstResult, maxResults);
		return new DefaultResultInfos<Pool>(firstResult + pools.size(), -1, pools);
	}

	@Override
	public QItemType createItemType(String type, boolean deletable) {
		return qpoolItemTypeDao.create(type, deletable);
	}

	@Override
	public List<QItemType> getAllItemTypes() {
		return qpoolItemTypeDao.getItemTypes();
	}
	
	@Override
	public QItemType getItemType(String type) {
		return qpoolItemTypeDao.loadByType(type);
	}

	@Override
	public boolean delete(QItemType itemType) {
		if(qpoolItemTypeDao.countItemUsing(itemType) == 0) {
			return qpoolItemTypeDao.delete(itemType);
		}
		return false;
	}

	@Override
	public QEducationalContext createEducationalContext(String level) {
		return qEduContextDao.create(level, true);
	}

	@Override
	public List<QEducationalContext> getAllEducationlContexts() {
		return qEduContextDao.getEducationalContexts();
	}

	@Override
	public QEducationalContext getEducationlContextByLevel(String level) {
		return qEduContextDao.loadByLevel(level);
	}

	@Override
	public boolean deleteEducationalContext(QEducationalContext context) {
		if(qEduContextDao.isEducationalContextInUse(context)) {
			return false;
		}
		return qEduContextDao.delete(context);
	}

	@Override
	public QLicense createLicense(String licenseKey, String text) {
		return qpoolLicenseDao.create(licenseKey, text, true);
	}

	@Override
	public List<QLicense> getAllLicenses() {
		return qpoolLicenseDao.getLicenses();
	}

	@Override
	public QLicense getLicense(String licenseKey) {
		return qpoolLicenseDao.loadByLicenseKey(licenseKey);
	}

	@Override
	public QLicense updateLicense(QLicense license) {
		return qpoolLicenseDao.update(license);
	}

	@Override
	public boolean deleteLicense(QLicense license) {
		return qpoolLicenseDao.delete(license);
	}
	
	private TaxonomyRef getQPoolTaxonomyRef() {
		String key = qpoolModule.getTaxonomyQPoolKey();
		try {
			return new TaxonomyRefImpl(new Long(key));
		} catch (NumberFormatException e) {
			log.error("", e);
			return null;
		}
	}
	
	public Taxonomy getQPoolTaxonomy() {
		TaxonomyRef ref = getQPoolTaxonomyRef() ;
		return ref == null ? null: taxonomyDao.loadByKey(ref.getKey());
	}

	@Override
	public List<TaxonomyLevel> getTaxonomyLevels() {
		TaxonomyRef qpoolTaxonomy = getQPoolTaxonomyRef();
		if(qpoolTaxonomy == null) {
			return new ArrayList<>();
		}
		return taxonomyLevelDao.getLevels(qpoolTaxonomy);
	}

	@Override
	public List<TaxonomyLevel> getTaxonomyLevelBy(TaxonomyLevel parent, String displayName) {
		Taxonomy qpoolTaxonomy = getQPoolTaxonomy();
		if(qpoolTaxonomy == null) {
			return new ArrayList<>();
		}
		return taxonomyLevelDao.getLevelsByDisplayName(qpoolTaxonomy, displayName);
	}

	@Override
	public TaxonomyLevel createTaxonomyLevel(TaxonomyLevel parent, String identifier, String displayName) {
		if (!qpoolModule.isImportCreateTaxonomyLevel()) return null;
		
		Taxonomy qpoolTaxonomy = getQPoolTaxonomy();
		if(qpoolTaxonomy == null) {
			return null;
		}
		return taxonomyLevelDao.createTaxonomyLevel(identifier, displayName, "", null, null, parent, null, qpoolTaxonomy);
	}
	
	@Override
	public List<TaxonomyLevel> getTaxonomyLevel(Identity identity, TaxonomyCompetenceTypes... competenceType) {
		return taxonomyCompetenceDao.getCompetencesByTaxonomy(getQPoolTaxonomyRef(), identity, new Date(), competenceType).stream()
				.map(competence -> competence.getTaxonomyLevel())
				.collect(Collectors.toList());
	}
	
	@Override
	public void resetAllStatesToDraft(Identity reseter) {
		questionItemDao.resetAllStatesToDraft();
		log.info("The states of all questions in the question bank were reseted to the status 'draft' by " + reseter);
	}

	@Override
	public void rateItemInReview(QuestionItem item, Identity identity, Float rating, String comment) {
		if (item == null || identity == null) return;
		
		if (rating != null && rating > 0f) {
			// Review is only in status review possible
			QuestionItem previousItem = loadItemById(item.getKey());
			if (QuestionStatus.review.equals(previousItem.getQuestionStatus())) {
				Integer newRating = Float.valueOf(rating).intValue();
				commentAndRatingService.createRating(identity, item, null, newRating);
				ReviewDecision decision = reviewService.decideStatus(item, rating);
				if (item instanceof QuestionItemImpl && decision.isStatusChanged()) {
					QuestionItemImpl itemImpl = (QuestionItemImpl) item;
					itemImpl.setQuestionStatus(decision.getStatus());
					updateItem(itemImpl);
				}
			}
		}
		if (StringHelper.containsNonWhitespace(comment)) {
			commentAndRatingService.createComment(identity, item, null, comment);
		}
	}

}