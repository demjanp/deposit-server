// Create this sample in a new, selected Namespace. Set the `token` parameter
// with :param {token: '...'} in Cypher Shell before sourcing this file.
CALL deposit.apply.batch([
  {
    key: 'specimenClass',
    procedure: 'deposit.create.class',
    arguments: ['Specimen', {descriptors: [
      {name: 'Code', type: 'String'},
      {name: 'Mass', type: 'Float'}
    ]}]
  },
  {
    key: 'contextClass',
    procedure: 'deposit.create.class',
    arguments: ['Context', {descriptors: [{name: 'Code', type: 'String'}]}]
  },
  {
    key: 'specimen1',
    procedure: 'deposit.create.object',
    arguments: [
      {ref: 'specimenClass', field: 'id'},
      {descriptors: {Code: 'S-01', Mass: 10.0}}
    ]
  },
  {
    key: 'specimen2',
    procedure: 'deposit.create.object',
    arguments: [
      {ref: 'specimenClass', field: 'id'},
      {descriptors: {Code: 'S-02', Mass: 20.0}}
    ]
  },
  {
    key: 'specimen3',
    procedure: 'deposit.create.object',
    arguments: [
      {ref: 'specimenClass', field: 'id'},
      {descriptors: {Code: 'S-03'}}
    ]
  },
  {
    key: 'context1',
    procedure: 'deposit.create.object',
    arguments: [
      {ref: 'contextClass', field: 'id'},
      {descriptors: {Code: 'C-01'}}
    ]
  },
  {
    key: 'found1',
    procedure: 'deposit.create.relationship',
    arguments: [
      {ref: 'specimen1', field: 'id'},
      {ref: 'context1', field: 'id'},
      'foundIn',
      {}
    ]
  },
  {
    key: 'found2',
    procedure: 'deposit.create.relationship',
    arguments: [
      {ref: 'specimen2', field: 'id'},
      {ref: 'context1', field: 'id'},
      'foundIn',
      {}
    ]
  }
], $token) YIELD data
RETURN data;

