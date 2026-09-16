// Disposable synthetic fixture; Mongo initialization, never BootUI, owns index creation.
const sample = db.getSiblingDB('bootui_sample');
sample.createUser({
  user: process.env.BOOTUI_SAMPLE_MONGODB_USERNAME,
  pwd: process.env.BOOTUI_SAMPLE_MONGODB_PASSWORD,
  roles: [{ role: 'readWrite', db: 'bootui_sample' }]
});
sample.sample_mongo_products.insertMany([
  { _id: 'starter', sku: 'BOOTUI-STARTER', name: 'Document starter', category: 'tools', available: true,
    details: { material: 'digital', tags: ['local', 'sample'] }, externalReference: 'sample-starter' },
  { _id: 'guide', sku: 'BOOTUI-GUIDE', name: 'Document guide', category: 'books', available: true,
    details: { material: 'paper', tags: ['sample'] } },
  { _id: 'kit', sku: 'BOOTUI-KIT', name: 'Document kit', category: 'tools', available: false,
    details: { material: 'digital', tags: ['sample'] } }
]);
sample.sample_mongo_products.createIndex({ sku: 1 }, { name: 'sku_unique', unique: true });
sample.sample_mongo_products.createIndex({ category: 1, available: 1 }, { name: 'category_available' });
sample.sample_mongo_products.createIndex({ externalReference: 1 }, { name: 'external_reference_sparse', sparse: true });
sample.sample_mongo_products.createIndex({ name: 1 }, {
  name: 'name_partial',
  partialFilterExpression: { category: 'BOOTUI_PARTIAL_LITERAL_MUST_NOT_LEAK' }
});
sample.sample_mongo_events.insertOne({ _id: 'welcome', createdAt: new Date(), kind: 'synthetic' });
sample.sample_mongo_events.createIndex({ createdAt: 1 }, { name: 'event_expiry', expireAfterSeconds: 3600 });
