// The sample data of the MongoDB test server, run by the image's mongosh as the root user.
const hoori = db.getSiblingDB('hoori');

hoori.customers.insertMany([
  { _id: 1, name: 'Ada', email: 'ada@example.com', born: ISODate('1815-12-10T00:00:00Z'),
    tags: ['math', 'poetry'], address: { city: 'London', zip: 'W1' } },
  { _id: 2, name: 'Grace', email: 'grace@example.com', born: ISODate('1906-12-09T00:00:00Z'),
    tags: ['navy'] },
  { _id: 3, name: 'Edsger', born: ISODate('1930-05-11T00:00:00Z'), score: NumberLong(42) },
]);

const orders = [];
for (let i = 1; i <= 250; i++) {
  orders.push({ _id: i, customer: (i % 3) + 1, total: NumberDecimal(String(i * 1.5)),
    items: i % 5, paid: i % 2 === 0 });
}
hoori.orders.insertMany(orders);
hoori.orders.createIndex({ customer: 1 });

hoori.createView('paid_orders', 'orders', [{ $match: { paid: true } }]);
hoori.createCollection('empty');
