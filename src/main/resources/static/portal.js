const questionnaire = document.querySelector('.questionnaire');
const progress = document.querySelector('#progress');
if (questionnaire && progress) {
    const fields = [...questionnaire.querySelectorAll('.field input, .field select')];
    const update = () => {
        const answered = fields.filter(field => field.value.trim() !== '').length;
        progress.textContent = `${answered} of ${fields.length} answered`;
    };
    progress.hidden = false;
    questionnaire.addEventListener('input', update);
    update();
}

const back = document.querySelector('[data-back]');
if (back && new URL(document.referrer || location.href).pathname.startsWith('/check/')) {
    back.hidden = false;
    back.addEventListener('click', () => history.back());
}

const language = document.querySelector('#question-language');
if (language) {
    const strings = [...questionnaire.querySelectorAll('[data-hi], [data-pa], [data-ta]')]
        .map(node => ({node, english: node.textContent}));
    const choices = {
        en: ['Choose an answer', 'Yes', 'No', 'Yes, applying individually', 'No, applying as a group or another entity'],
        hi: ['उत्तर चुनें', 'हाँ', 'नहीं', 'हाँ, व्यक्तिगत रूप से', 'नहीं, समूह या अन्य संस्था के रूप में'],
        pa: ['ਜਵਾਬ ਚੁਣੋ', 'ਹਾਂ', 'ਨਹੀਂ', 'ਹਾਂ, ਵਿਅਕਤੀਗਤ ਤੌਰ ’ਤੇ', 'ਨਹੀਂ, ਸਮੂਹ ਜਾਂ ਹੋਰ ਸੰਸਥਾ ਵਜੋਂ'],
        ta: ['பதிலைத் தேர்ந்தெடுக்கவும்', 'ஆம்', 'இல்லை', 'ஆம், தனிநபராக', 'இல்லை, குழுவாக அல்லது வேறு அமைப்பாக']
    };
    const inputChoices = [...questionnaire.querySelectorAll('.field select')];
    const translate = () => {
        const locale = language.value;
        strings.forEach(({node, english}) => {
            const translation = node.dataset[locale];
            node.textContent = translation ?? english;
            node.lang = translation === undefined ? 'en' : locale;
        });
        const [placeholder, yes, no, individual, group] = choices[locale];
        inputChoices.forEach(select => {
            const applicantType = select.options[1].value === 'individual';
            const labels = [placeholder, applicantType ? individual : yes, applicantType ? group : no];
            [...select.options].forEach((option, index) => {
                option.textContent = labels[index];
                option.lang = locale;
            });
        });
    };
    document.querySelector('#language-picker').hidden = false;
    language.addEventListener('change', translate);
}
