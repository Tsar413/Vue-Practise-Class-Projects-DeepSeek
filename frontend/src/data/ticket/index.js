import users from './users/operations.json' with { type: 'json' }
import activities from './activities/operations.json' with { type: 'json' }
import registration from './registration/operations.json' with { type: 'json' }
import tickets from './tickets/operations.json' with { type: 'json' }

// 抢票项目的接口定义，按功能分组由 doc-groups.js 完成归类。
export default [...users, ...activities, ...registration, ...tickets]
