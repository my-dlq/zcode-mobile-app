// Run with: node --test app/src/test/claim-campaign-script.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../main/java/ai/zcode/remote/ui/remote/event/ClaimCampaignScript.kt'), 'utf8')
  .match(/val js = """([\s\S]*?)"""/)[1];

const delivery = {scope:'fixture-account', serverTime:100, deliveries:[{campaign_id:'fixture-campaign', banner:{
  background:{args:{zcode_plan:{name:'Fixture offer', entitlements:[{show_name:'Test model',grant_units:100000000,unit_type:'token'}]}}},
  buttons:[{action:{type:'claim_zcode_plan',args:{plan_id:'fixture-plan'}}}]
}}]};

function harness({previewOnly=false, success=true, interactive=false, taskSession=false, missingCaptcha=false, officialBanner=false,
  rpcVersion='current', configError=null}={}) {
  const state={queries:0,claims:0,configCalls:[],reports:[],deliveries:[],clicks:[],statuses:[],now:1000000};
  const nodes=new Map();
  function element(tag='div') {
    const e={tagName:tag.toUpperCase(),children:[],dataset:{},style:{},hidden:false,textContent:'',
      setAttribute(){},appendChild(child){this.children.push(child);if(child.id)nodes.set(child.id,child);},
      remove(){if(this.id)nodes.delete(this.id);},insertAdjacentElement(_,child){if(child.id)nodes.set(child.id,child);},
      click(){this.onclick?.({stopPropagation(){}});}};
    return e;
  }
  const header=element(), parent=element(), heading=element('h1');
  heading.textContent=taskSession?'Task conversation':'当前设备上的工作区和任务';heading.parentElement=parent;parent.parentElement=header;
  const footerBar=element(), footerRow=element(), footer=element('button');
  footer.setAttribute=function(key,value){this[key]=value;if(key==='data-testid'&&value==='login-trigger')nodes.set('login-trigger',this);};
  footer.setAttribute('data-testid','login-trigger');footer.parentElement=footerRow;footerRow.parentElement=footerBar;
  footerBar.insertBefore=function(child,before){if(before!==footerRow)throw Error('unexpected footer insertion');this.children.unshift(child);if(child.id)nodes.set(child.id,child);};
  const official=element();
  official.setAttribute('data-testid','manual-claim-plan-banner');
  official.scrollIntoView=()=>{state.officialScrolls=(state.officialScrolls||0)+1;};
  official.click=()=>{state.officialClicks=(state.officialClicks||0)+1;};
  const svc={
    marketingTouchService:{async query(){state.queries++;if(previewOnly)throw Error('unsupported');return delivery;},async report(x){state.reports.push(x);}},
    codingPlanSubscriptionService:{async getManualClaimPlanPreviews(){return {plans:[{planId:'fixture-plan',name:'Fixture offer'}]};},
      async getRequestVerificationConfig(){if(configError)throw Error(configError);return {enabled:true,sceneId:'fixture-scene',region:'cn'};},
      async claimManualPlan(args){state.claims++;assert.equal(args.captchaVerifyParam,'fixture-verification');return {success,code:3007};}}
  };
  if(rpcVersion==='legacy') {
    svc.codingPlanSubscriptionService.getCaptchaConfig=svc.codingPlanSubscriptionService.getRequestVerificationConfig;
    delete svc.codingPlanSubscriptionService.getRequestVerificationConfig;
  }
  if(missingCaptcha)delete svc.codingPlanSubscriptionService.getRequestVerificationConfig;
  else {
    const implementation=svc.codingPlanSubscriptionService;
    // 模拟真实 RPC：不存在的方法在读取时仍是函数，执行时才返回 Method not found。
    svc.codingPlanSubscriptionService=new Proxy({}, {get(_,method){
      if(method==='then')return undefined;
      return async(...args)=>{
        if(/Config$/.test(method))state.configCalls.push(method);
        if(typeof implementation[method]!=='function')throw Error(`Method not found: ${method}`);
        return implementation[method](...args);
      };
    }});
  }
  const root={memoizedState:{memoizedState:svc}}, fiber={__reactFiberFixture:root};
  const context={console,Set,Promise,Date:{now:()=>state.now},
    setTimeout(){return 1;},clearTimeout(){},setInterval(){},MutationObserver:class{observe(){}},
    document:{createElement:element,body:element(),head:element(),
      getElementById:id=>nodes.get(id),querySelectorAll:selector=>{
        if(selector==='*')return [fiber];if(selector==='h1')return [heading];
        if(selector==='[data-testid="login-trigger"]')return taskSession?[]:[footer];
        if(selector==='#zcode-claim-offers button')return (nodes.get('zcode-claim-offers')?.children||[]).flatMap(c=>c.children).filter(c=>c.tagName==='BUTTON');
        return [];
      },querySelector:selector=>{
        if(selector==='[data-testid="login-trigger"]'&&!taskSession)return footer;
        if(selector==='[data-testid="manual-claim-plan-banner"]')return officialBanner?official:null;
        return null;
      }}};
  context.window={addEventListener(){},__zcodeNative:{onClaimCampaigns:body=>state.deliveries.push(JSON.parse(body)),onClaimCampaignClicked:key=>state.clicks.push(key),
    onClaimCampaignStatus:(key,message,finished)=>state.statuses.push({key,message,finished})},
    initAliyunCaptcha:cfg=>{
      nodes.get('zcode-claim-captcha-trigger').onclick=()=>cfg.success('fixture-verification');
      cfg.getInstance({destroy(){},startTracelessVerification(){
        if(interactive)cfg.fail();else cfg.success('fixture-verification');
      }});
    }};
  vm.runInNewContext(source,context);
  return {state,context,nodes,footerBar,buttons:()=>context.document.querySelectorAll('#zcode-claim-offers button'),
    settle:()=>new Promise(resolve=>setImmediate(resolve))};
}

test('discovery renders the claim banner in the task-list footer without claiming',async()=>{
  const h=harness();await h.settle();
  assert.equal(h.state.claims,0);assert.deepEqual(h.state.clicks,[]);assert.deepEqual(h.state.reports,[]);
  assert.equal(h.state.deliveries.length,1);assert.equal(h.buttons().length,1);
  assert.equal(h.nodes.has('zcode-claim-offers'),true);
  assert.equal(h.footerBar.children[0],h.nodes.get('zcode-claim-status'));
  assert.equal(h.footerBar.children[1],h.nodes.get('zcode-claim-offers'));
  const offer=h.nodes.get('zcode-claim-offers').children[0];
  assert.equal(offer.children[0].textContent,'Fixture offer');
  assert.equal(offer.children[1].textContent,'1 亿 TOKENS');
  assert.equal(offer.children[2].textContent,'Test model');
  assert.equal(h.state.deliveries[0].deliveries[0].banner.background.args.zcode_plan.entitlements[0].grant_units,100000000);
});
test('banner claim uses the same single verification and reports success',async()=>{
  const h=harness();await h.settle();
  h.buttons()[0].onclick({stopPropagation(){}});await h.settle();
  assert.equal(h.state.claims,1);assert.equal(h.state.clicks.length,1);
  assert.equal(h.state.reports[0].actionType,'confirm');
  assert.equal(h.nodes.has('zcode-claim-offers'),false);
  assert.equal(h.nodes.get('zcode-claim-status').textContent,'Fixture offer 领取成功');
});
test('an outdated official banner cannot redirect the claim back to the removed RPC',async()=>{
  const h=harness({officialBanner:true});await h.settle();
  h.buttons()[0].onclick({stopPropagation(){}});await h.settle();
  assert.equal(h.state.officialClicks,undefined);
  assert.deepEqual(h.state.configCalls,['getRequestVerificationConfig']);
  assert.equal(h.state.claims,1);
});
test('legacy RPC falls back only after the current config method is rejected as missing',async()=>{
  const h=harness({rpcVersion:'legacy',taskSession:true});await h.settle();
  assert.equal(h.context.window.__zcodeClaims.claim('fixture-account|fixture-campaign|fixture-plan'),true);
  await h.settle();
  assert.deepEqual(h.state.configCalls,['getRequestVerificationConfig','getCaptchaConfig']);
  assert.equal(h.state.claims,1);
});
test('config transport failure does not call another method or submit a claim',async()=>{
  const h=harness({configError:'Connection closed'});await h.settle();
  h.buttons()[0].onclick({stopPropagation(){}});await h.settle();
  assert.deepEqual(h.state.configCalls,['getRequestVerificationConfig']);
  assert.equal(h.state.claims,0);
  assert.equal(h.state.reports.length,0);
  assert.match(h.state.statuses.at(-1).message,/Connection closed/);
});
test('polling uses desktop cadence while later server deliveries still reach native',async()=>{
  const h=harness();await h.settle();await h.context.window.__zcodeClaims.refresh(false);
  assert.equal(h.state.queries,1);
  h.state.now+=600001;await h.context.window.__zcodeClaims.refresh(false);
  assert.equal(h.state.queries,2);assert.equal(h.state.deliveries.length,2);assert.equal(h.state.claims,0);
});
test('explicit fixture click verifies once and reports successful confirmation',async()=>{
  const h=harness();await h.settle();const key='fixture-account|fixture-campaign|fixture-plan';
  h.context.window.__zcodeClaims.claim(key);h.context.window.__zcodeClaims.claim(key);await h.settle();
  assert.equal(h.state.claims,1);assert.equal(h.state.clicks.length,1);
  assert.equal(h.state.reports[0].actionType,'confirm');
  assert.match(h.state.statuses.at(-1).message,/领取成功/);
  assert.equal(h.state.statuses.at(-1).finished,true);
});
test('failed fixture claim remains available and never reports success',async()=>{
  const h=harness({success:false});await h.settle();
  const key='fixture-account|fixture-campaign|fixture-plan';
  h.context.window.__zcodeClaims.claim(key);await h.settle();
  assert.equal(h.state.claims,1);assert.equal(h.state.reports.length,0);
  assert.match(h.state.statuses.at(-1).message,/验证码校验失败/);
  assert.equal(h.state.statuses.at(-1).finished,true);
  assert.equal(h.context.window.__zcodeClaims.claim(key),true);await h.settle();assert.equal(h.state.claims,2);
});
test('interactive SDK fallback completes verification before submitting a fixture claim',async()=>{
  const h=harness({interactive:true});await h.settle();
  h.context.window.__zcodeClaims.claim('fixture-account|fixture-campaign|fixture-plan');await h.settle();
  assert.equal(h.state.claims,1);assert.match(h.state.statuses.at(-1).message,/领取成功/);
});
test('older desktop previews keep a claim entry without inventing a server delivery',async()=>{
  const h=harness({previewOnly:true});await h.settle();assert.equal(h.buttons().length,1);
  assert.equal(h.state.deliveries.length,0);assert.equal(h.state.claims,0);
});

test('a task conversation delivers native reminders without a task-list banner or automatic claim',async()=>{
  const h=harness({taskSession:true});await h.settle();
  assert.equal(h.buttons().length,0);assert.equal(h.nodes.has('zcode-claim-offers'),false);
  assert.equal(h.state.deliveries.length,1);
  assert.equal(h.state.claims,0);assert.deepEqual(h.state.clicks,[]);assert.deepEqual(h.state.statuses,[]);
  h.state.now+=600001;await h.context.window.__zcodeClaims.refresh(false);
  assert.equal(h.state.deliveries.length,2);assert.equal(h.state.claims,0);
});

test('native fixture action in a conversation claims once and reports its final result without a status element',async()=>{
  const h=harness({taskSession:true});await h.settle();
  const key='fixture-account|fixture-campaign|fixture-plan';
  assert.equal(h.context.window.__zcodeClaims.claim(key),true);
  assert.equal(h.context.window.__zcodeClaims.claim(key),false);
  await h.settle();
  assert.equal(h.state.claims,1);assert.deepEqual(h.state.clicks,[key]);
  const final=h.state.statuses.at(-1);
  assert.equal(final.key,key);assert.equal(final.finished,true);assert.match(final.message,/领取成功/);
});

test('a stale notification cannot claim or acknowledge another offer',async()=>{
  const h=harness({taskSession:true});await h.settle();
  assert.equal(h.context.window.__zcodeClaims.claim('another-account|fixture-campaign|fixture-plan'),false);
  assert.equal(h.state.claims,0);assert.deepEqual(h.state.clicks,[]);assert.deepEqual(h.state.reports,[]);
});
test('an incomplete service object is not treated as the plan service',async()=>{
  const h=harness({missingCaptcha:true});await h.settle();
  assert.equal(h.state.queries,0);assert.equal(h.buttons().length,0);
});
